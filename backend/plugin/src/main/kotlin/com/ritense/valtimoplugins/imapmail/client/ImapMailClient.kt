/*
 * Copyright 2026 Ritense BV, the Netherlands.
 *
 * Licensed under EUPL, Version 1.2 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ritense.valtimoplugins.imapmail.client

import com.ritense.valtimoplugins.imapmail.domain.AuthenticationMode
import com.ritense.valtimoplugins.imapmail.domain.ImapMailConnectionProperties
import com.ritense.valtimoplugins.imapmail.domain.PostProcessAction
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import jakarta.mail.search.FlagTerm
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Properties

/**
 * Opens a mailbox, hands each candidate message to a handler, and applies the configured
 * post-processing to the ones the handler accepted.
 *
 * The handler runs while the folder is still open, on purpose: a `MimeMessage` is a lazy
 * view onto the connection, and anything not read before [Folder.close] is unreachable
 * afterwards. Callers must therefore extract everything they need inside the callback.
 */
open class ImapMailClient(
    private val tokenClient: OAuth2TokenClient,
) {
    /**
     * Polls one mailbox.
     *
     * Failures are deliberately asymmetric. A connection or authentication failure aborts
     * the whole poll — nothing useful can follow it. A failure on a single message is
     * logged and counted, and the poll continues with the next one, so a single malformed
     * mail cannot wedge the mailbox forever.
     */
    open fun poll(
        connection: ImapMailConnectionProperties,
        handler: MailHandler,
    ): PollResult {
        connection.validate()

        val session = session(connection)
        val store = session.getStore(connection.protocol.schemeName)

        // Connecting inside `use` rather than before it, so that a store left half-open by a
        // failed connect is still closed. A poll that fails on every run - an expired secret,
        // say - would otherwise leak one store per interval, for as long as nobody notices.
        return store.use { openStore ->
            try {
                connect(openStore, connection)
            } catch (e: AuthenticationFailedException) {
                // A cached token may have been revoked before its stated expiry; drop it so
                // the next poll fetches a new one rather than failing identically forever.
                if (connection.authentication == AuthenticationMode.XOAUTH2) {
                    connection.oauth?.let { tokenClient.invalidate(it) }
                }
                throw e
            }

            val folder = openStore.getFolder(connection.folder)
            require(folder.exists()) { "Folder '${connection.folder}' does not exist on ${connection.host}" }

            // NONE must not touch the mailbox at all, and merely reading a body over IMAP in
            // read-write mode sets \Seen. Read-only keeps that promise.
            val readOnly = connection.postProcessAction == PostProcessAction.NONE
            folder.open(if (readOnly) Folder.READ_ONLY else Folder.READ_WRITE)

            try {
                handleMessages(folder, connection, handler)
            } finally {
                // Expunge only when something was actually flagged deleted; POP3 in
                // particular treats close(true) as "commit the deletions".
                val expunge =
                    connection.postProcessAction == PostProcessAction.DELETE ||
                        connection.postProcessAction == PostProcessAction.MOVE
                runCatching { folder.close(expunge) }
                    .onFailure { logger.warn(it) { "Failed to close folder '${connection.folder}' cleanly" } }
            }
        }
    }

    private fun handleMessages(
        folder: Folder,
        connection: ImapMailConnectionProperties,
        handler: MailHandler,
    ): PollResult {
        val candidates = candidates(folder, connection)
        if (candidates.isEmpty()) {
            logger.debug { "No candidate messages in ${connection.describe()}" }
            return PollResult()
        }

        logger.debug { "Fetched ${candidates.size} candidate message(s) from ${connection.describe()}" }

        var handled = 0
        var skipped = 0
        var failed = 0
        val toPostProcess = mutableListOf<Message>()

        candidates.forEach { message ->
            val identity = identityOf(message, folder, connection)
            try {
                if (handler.handle(message, identity)) {
                    handled++
                    toPostProcess += message
                } else {
                    skipped++
                    // A skipped message was either a duplicate or matched no process link.
                    // Post-process it anyway: leaving it behind means re-fetching and
                    // re-parsing it on every single poll from here on.
                    toPostProcess += message
                }
            } catch (e: Exception) {
                failed++
                logger.error(e) {
                    "Failed to handle message '$identity' from ${connection.describe()}; leaving it on the server"
                }
            }
        }

        postProcess(toPostProcess, folder, connection)

        return PollResult(fetched = candidates.size, handled = handled, skipped = skipped, failed = failed)
    }

    /**
     * Selects the messages worth looking at.
     *
     * With [PostProcessAction.MARK_READ] the server can do the filtering, so only unseen
     * messages come back. The other actions remove the message from the folder (or leave
     * everything untouched), so there the whole folder is the candidate set and duplicate
     * detection falls to the processed-mail table.
     */
    private fun candidates(
        folder: Folder,
        connection: ImapMailConnectionProperties,
    ): List<Message> {
        val found =
            if (connection.protocol.supportsFlags && connection.postProcessAction == PostProcessAction.MARK_READ) {
                folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            } else {
                folder.messages
            }

        return found
            .asSequence()
            .filterNot { runCatching { it.isSet(Flags.Flag.DELETED) }.getOrDefault(false) }
            .take(connection.maxMessagesPerPoll)
            .toList()
    }

    /**
     * Builds the key used to recognise a message the plugin has already seen.
     *
     * `Message-ID` is preferred because it survives a move between folders and is stable
     * across UID validity resets. It is only a SHOULD in RFC 5322, so there are two
     * fallbacks: the IMAP UID (unique within a folder, qualified with UIDVALIDITY so a
     * server-side renumbering does not alias old keys onto new messages), and finally a
     * digest over the headers that pin a message down.
     */
    internal fun identityOf(
        message: Message,
        folder: Folder,
        connection: ImapMailConnectionProperties,
    ): String {
        (message as? MimeMessage)
            ?.let { runCatching { it.messageID }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { return "msgid:$it" }

        if (folder is UIDFolder) {
            runCatching { "${folder.uidValidity}/${folder.getUID(message)}" }
                .getOrNull()
                ?.let { return "uid:${connection.host}/${connection.folder}/$it" }
        }

        return "digest:${digestOf(message, connection)}"
    }

    private fun digestOf(
        message: Message,
        connection: ImapMailConnectionProperties,
    ): String {
        val material =
            buildString {
                append(connection.host).append('/').append(connection.folder).append('/')
                append(runCatching { message.from?.joinToString() }.getOrNull()).append('|')
                append(runCatching { message.subject }.getOrNull()).append('|')
                append(runCatching { message.sentDate?.time }.getOrNull()).append('|')
                append(runCatching { message.receivedDate?.time }.getOrNull()).append('|')
                append(runCatching { message.size }.getOrNull())
            }
        return MessageDigest
            .getInstance("SHA-256")
            .digest(material.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun postProcess(
        messages: List<Message>,
        folder: Folder,
        connection: ImapMailConnectionProperties,
    ) {
        if (messages.isEmpty() || connection.postProcessAction == PostProcessAction.NONE) return

        try {
            when (connection.postProcessAction) {
                PostProcessAction.MARK_READ ->
                    folder.setFlags(messages.toTypedArray(), Flags(Flags.Flag.SEEN), true)

                PostProcessAction.DELETE ->
                    folder.setFlags(messages.toTypedArray(), Flags(Flags.Flag.DELETED), true)

                PostProcessAction.MOVE -> {
                    val target = folder.store.getFolder(requireNotNull(connection.targetFolder))
                    if (!target.exists()) {
                        require(target.create(Folder.HOLDS_MESSAGES)) {
                            "Target folder '${connection.targetFolder}' does not exist and could not be created"
                        }
                    }
                    // Copy first, flag deleted second. The reverse order risks losing mail if
                    // the copy fails after the delete has been committed.
                    folder.copyMessages(messages.toTypedArray(), target)
                    folder.setFlags(messages.toTypedArray(), Flags(Flags.Flag.DELETED), true)
                }

                PostProcessAction.NONE -> Unit
            }
            logger.debug {
                "Applied ${connection.postProcessAction} to ${messages.size} message(s) in ${connection.describe()}"
            }
        } catch (e: Exception) {
            // The processes have already been started at this point, so failing here would
            // roll nothing back. Log loudly instead: the processed-mail table stops the
            // duplicate that the un-applied flag would otherwise cause on the next poll.
            logger.error(e) {
                "Started process(es) for ${messages.size} message(s) but could not apply " +
                    "${connection.postProcessAction} in ${connection.describe()}. Duplicate handling is now " +
                    "relying on the processed-mail table."
            }
        }
    }

    private fun connect(
        store: Store,
        connection: ImapMailConnectionProperties,
    ) {
        val credential =
            when (connection.authentication) {
                AuthenticationMode.BASIC ->
                    requireNotNull(connection.password) { "password is required for BASIC authentication" }
                AuthenticationMode.XOAUTH2 -> tokenClient.accessToken(requireNotNull(connection.oauth))
            }
        store.connect(connection.host, connection.port, connection.username, credential)
        logger.debug { "Connected to ${connection.describe()}" }
    }

    internal fun session(connection: ImapMailConnectionProperties): Session {
        val scheme = connection.protocol.schemeName
        val properties =
            Properties().apply {
                this["mail.store.protocol"] = scheme
                this["mail.$scheme.host"] = connection.host
                this["mail.$scheme.port"] = connection.port.toString()
                this["mail.$scheme.connectiontimeout"] = CONNECT_TIMEOUT_MILLIS
                this["mail.$scheme.timeout"] = READ_TIMEOUT_MILLIS
                this["mail.$scheme.writetimeout"] = READ_TIMEOUT_MILLIS

                if (connection.protocol.implicitTls) {
                    this["mail.$scheme.ssl.enable"] = "true"
                } else if (connection.startTlsEnable) {
                    this["mail.$scheme.starttls.enable"] = "true"
                    // Without this, a server that does not offer STARTTLS is silently spoken
                    // to in the clear - credentials included.
                    this["mail.$scheme.starttls.required"] = "true"
                }

                // Verify the server certificate against the JVM trust store. This is the
                // default, but an explicit value means ambient configuration cannot turn
                // transport security into a no-op without someone editing this line.
                this["mail.$scheme.ssl.checkserveridentity"] = "true"

                if (connection.authentication == AuthenticationMode.XOAUTH2) {
                    this["mail.$scheme.auth.mechanisms"] = "XOAUTH2"
                    // Belt and braces: without these a server that rejects XOAUTH2 can make
                    // the client fall back to sending the bearer token as a plain password.
                    this["mail.$scheme.auth.login.disable"] = "true"
                    this["mail.$scheme.auth.plain.disable"] = "true"
                }

                // Keep the AUTH exchange out of the trace even when debug is on. This is the
                // JavaMail default; set explicitly so ambient configuration cannot flip it.
                this["mail.debug.auth"] = "false"
            }

        // Build the session ourselves so the protocol trace goes through the logger instead
        // of stdout, where it would bypass log level, appender and retention configuration.
        // Debug is switched on via Session.setDebug rather than the `mail.debug` property
        // because the property is read as a String and would trace the Session construction
        // itself to stdout before there is any chance to redirect it.
        return Session.getInstance(properties).apply {
            debugOut = PrintStream(LineLoggingOutputStream(), true, StandardCharsets.UTF_8)
            setDebug(connection.debug)

            if (connection.debug) {
                logger.warn {
                    "Mail store debug tracing is enabled for host '${connection.host}'. The full protocol dialogue - " +
                        "headers, recipients and message content - is written to logger '$DEBUG_LOGGER_NAME' at DEBUG level."
                }
            }
        }
    }

    /** Collects the protocol trace line by line and hands each line to the logger. */
    private class LineLoggingOutputStream : OutputStream() {
        private val line = ByteArrayOutputStream(INITIAL_LINE_BUFFER_SIZE)

        override fun write(b: Int) {
            when (b) {
                '\n'.code -> flushLine()
                '\r'.code -> Unit
                else -> line.write(b)
            }
        }

        override fun flush() = flushLine()

        override fun close() = flushLine()

        private fun flushLine() {
            if (line.size() == 0) return
            val message = line.toString(StandardCharsets.UTF_8)
            line.reset()
            logger.debug { message }
        }
    }

    /**
     * Handles one fetched message.
     *
     * Returns `true` when a process was started for it, `false` when it was knowingly
     * skipped (a duplicate, or matching no process link). Either way the message gets
     * post-processed; throwing is what leaves it on the server for the next poll.
     */
    fun interface MailHandler {
        fun handle(
            message: Message,
            identity: String,
        ): Boolean
    }

    data class PollResult(
        val fetched: Int = 0,
        val handled: Int = 0,
        val skipped: Int = 0,
        val failed: Int = 0,
    )

    companion object {
        private val logger = KotlinLogging.logger {}

        private val DEBUG_LOGGER_NAME = ImapMailClient::class.java.name

        private const val CONNECT_TIMEOUT_MILLIS = "30000"
        private const val READ_TIMEOUT_MILLIS = "60000"
        private const val INITIAL_LINE_BUFFER_SIZE = 256
    }
}
