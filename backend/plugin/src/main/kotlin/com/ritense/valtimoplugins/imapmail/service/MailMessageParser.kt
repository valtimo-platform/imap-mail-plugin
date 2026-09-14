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

package com.ritense.valtimoplugins.imapmail.service

import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import com.ritense.valtimoplugins.imapmail.domain.MailAttachment
import com.ritense.valtimoplugins.imapmail.domain.MailRejectedException
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.internet.ContentType
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Turns a live `MimeMessage` into a detached [FetchedMail], writing the body and every
 * attachment into temporary resource storage as it goes.
 *
 * Must be called while the message's folder is still open — see `ImapMailClient.poll`.
 *
 * Nothing here trusts the message. Inbound mail is attacker-controlled input: filenames may
 * try to traverse directories, a part may claim a size it does not have, a multipart may
 * hold far more parts than any real mail, and a nested multipart may be deep enough to
 * exhaust the stack. Each of those is bounded below rather than assumed away.
 *
 * A mail that breaches one of those bounds is refused with a [MailRejectedException], which
 * the poller treats differently from an ordinary read failure — see that class.
 */
@SkipComponentScan
@Component
class MailMessageParser(
    private val storageService: TemporaryResourceStorageService,
) {
    fun parse(
        message: Message,
        identity: String,
    ): FetchedMail {
        val parts = collectParts(message)

        val body = parts.body()
        val bodyResourceId =
            storageService.store(
                ByteArrayInputStream(body.content.toByteArray(StandardCharsets.UTF_8)),
                mapOf(
                    MetadataType.FILE_NAME.key to if (body.isHtml) "mail-body.html" else "mail-body.txt",
                    MetadataType.CONTENT_TYPE.key to if (body.isHtml) "text/html" else "text/plain",
                ),
            )

        return FetchedMail(
            identity = identity,
            messageId = (message as? MimeMessage)?.let { runCatching { it.messageID }.getOrNull() },
            sender = senderAddress(message),
            senderName = senderName(message),
            recipients = addresses(message, Message.RecipientType.TO),
            ccRecipients = addresses(message, Message.RecipientType.CC),
            subject = runCatching { message.subject }.getOrNull(),
            receivedAt = runCatching { message.receivedDate?.toInstant() }.getOrNull(),
            sentAt = runCatching { message.sentDate?.toInstant() }.getOrNull(),
            references = threadReferences(message),
            bodyResourceId = bodyResourceId,
            bodyIsHtml = body.isHtml,
            attachments = parts.attachments,
        )
    }

    /**
     * Walks the MIME tree once, collecting body candidates and storing attachments.
     *
     * One pass rather than one per concern: every read of a part streams from the server, so
     * traversing twice would double the network cost of a large mail.
     */
    private fun collectParts(message: Message): CollectedParts {
        val collected = CollectedParts()
        walk(message, collected, depth = 0)
        return collected
    }

    private fun walk(
        part: Part,
        collected: CollectedParts,
        depth: Int,
    ) {
        if (depth > MAX_MULTIPART_DEPTH) {
            logger.warn { "Stopped walking MIME tree at depth $depth; deeper parts are ignored" }
            return
        }

        // Deliberately typed off the header rather than off `part.content`. Asking for the
        // content is what materialises a part, and doing that here would pull every
        // attachment and every body into the heap in full before the size caps below get a
        // say - which is the one thing this class must not do with attacker-supplied input.
        if (runCatching { part.isMimeType("multipart/*") }.getOrDefault(false)) {
            val multipart = runCatching { part.content as? Multipart }.getOrNull()
            if (multipart == null) {
                logger.warn { "Could not read a multipart body of type '${contentTypeOf(part)}'; ignoring it" }
                return
            }
            for (index in 0 until multipart.count) {
                val child = runCatching { multipart.getBodyPart(index) }.getOrNull() ?: continue
                walk(child, collected, depth + 1)
            }
            // In multipart/alternative the parts are the same content in different formats,
            // which is exactly the case where the HTML variant should win over the plain one.
            if (runCatching { part.isMimeType("multipart/alternative") }.getOrDefault(false)) {
                collected.preferHtml = true
            }
            return
        }

        if (isAttachment(part)) {
            storeAttachment(part, collected)
            return
        }

        when {
            runCatching { part.isMimeType("text/html") }.getOrDefault(false) ->
                collected.htmlBody = collected.htmlBody ?: readText(part)

            runCatching { part.isMimeType("text/plain") }.getOrDefault(false) ->
                collected.plainBody = collected.plainBody ?: readText(part)

            else ->
                logger.debug { "Ignoring MIME part of type '${contentTypeOf(part)}'" }
        }
    }

    /**
     * Message-IDs of the earlier mails in this thread.
     *
     * `References` carries the whole chain and `In-Reply-To` only the immediate parent, but
     * clients disagree on which they populate, so both are read. The ids are picked out with
     * a pattern instead of by splitting on whitespace: the headers are folded across lines
     * and some clients put comments between the ids.
     */
    private fun threadReferences(message: Message): List<String> =
        THREAD_HEADERS
            .flatMap { header ->
                runCatching { message.getHeader(header) }.getOrNull()?.filterNotNull().orEmpty()
            }.flatMap { value -> MESSAGE_ID_PATTERN.findAll(value).map { it.value }.toList() }
            .distinct()

    /**
     * Reads a text part, honouring the charset it declares.
     *
     * Falls back to UTF-8 rather than to the platform default, which would make the parsed
     * body depend on the locale of whichever node happened to poll.
     */
    private fun readText(part: Part): String? {
        val bytes =
            try {
                readBounded(part, MAX_BODY_BYTES) {
                    "Mail body exceeds the maximum of ${MAX_BODY_BYTES / BYTES_PER_MB} MB"
                }
            } catch (e: MailRejectedException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Could not read a text part of type '${contentTypeOf(part)}'; ignoring it" }
                return null
            }

        val charset =
            runCatching {
                Charset.forName(MimeUtility.javaCharset(ContentType(part.contentType).getParameter("charset")))
            }.getOrDefault(StandardCharsets.UTF_8)
        return String(bytes, charset)
    }

    /**
     * Reads a part into memory, refusing to allocate past [limit].
     *
     * Neither `Part.getContent()` nor `readAllBytes()` can be given a ceiling: both size
     * their buffer from what the stream produces, so a mail bigger than the cap would be
     * fully resident before anything had the chance to reject it. Checking as we go turns
     * "reject an oversized mail" from a promise into something the heap actually holds to.
     */
    private fun readBounded(
        part: Part,
        limit: Int,
        message: () -> String,
    ): ByteArray {
        val collected = ByteArrayOutputStream()
        part.inputStream.use { input ->
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (collected.size() + read > limit) throw MailRejectedException(message())
                collected.write(buffer, 0, read)
            }
        }
        return collected.toByteArray()
    }

    private fun contentTypeOf(part: Part): String? = runCatching { part.contentType }.getOrNull()

    /**
     * A part counts as an attachment when it says so, or when it has a filename.
     *
     * The filename check catches the common case of a mail client that omits
     * `Content-Disposition` entirely; without it those attachments would be parsed as body
     * text and the actual document would be lost.
     */
    private fun isAttachment(part: Part): Boolean {
        val disposition = runCatching { part.disposition }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) return true
        if (Part.INLINE.equals(disposition, ignoreCase = true)) {
            // Inline images belong to the body, not to the case file, unless they are the
            // only thing carrying a filename and a non-text type.
            return runCatching { part.fileName != null && !part.isMimeType("text/*") }.getOrDefault(false)
        }
        return runCatching { !part.fileName.isNullOrBlank() }.getOrDefault(false)
    }

    private fun storeAttachment(
        part: Part,
        collected: CollectedParts,
    ) {
        // Counted as well as weighed. The byte cap below says nothing about how many parts a
        // multipart may declare, and an empty part costs nothing against it - so without this
        // a mail of a hundred thousand zero-byte attachments would pass the size check while
        // writing a hundred thousand resources to storage and handing the process a variable
        // of a hundred thousand ids.
        if (collected.attachments.size >= MAX_ATTACHMENTS) {
            throw MailRejectedException("Mail has more than the maximum of $MAX_ATTACHMENTS attachments")
        }

        // The cap is on the mail as a whole, so each attachment may only use what its
        // predecessors left; a first attachment just under the limit must not let a second
        // one through.
        val bytes =
            try {
                readBounded(part, MAX_TOTAL_ATTACHMENT_BYTES - collected.attachmentBytes) {
                    "Mail attachments exceed the maximum of ${MAX_TOTAL_ATTACHMENT_BYTES / BYTES_PER_MB} MB"
                }
            } catch (e: MailRejectedException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "Could not read an attachment; skipping it" }
                return
            }
        collected.attachmentBytes += bytes.size

        val fileName = safeFileName(part, collected.attachments.size)
        val contentType = contentTypeOf(part)?.substringBefore(';')?.trim()

        val resourceId =
            storageService.store(
                ByteArrayInputStream(bytes),
                buildMap {
                    put(MetadataType.FILE_NAME.key, fileName)
                    contentType?.takeIf { it.isNotBlank() }?.let { put(MetadataType.CONTENT_TYPE.key, it) }
                },
            )

        collected.attachments +=
            MailAttachment(
                fileName = fileName,
                contentType = contentType,
                sizeInBytes = bytes.size.toLong(),
                resourceId = resourceId,
            )
    }

    /**
     * Derives a filename that is safe to hand to storage and to show in the UI.
     *
     * The name comes from the sender, so it is stripped of any path structure and of
     * characters that mean something to a filesystem. A name that survives none of that is
     * replaced by a positional fallback rather than dropped, so the attachment itself is
     * never lost.
     */
    internal fun safeFileName(
        part: Part,
        index: Int,
    ): String {
        val raw =
            runCatching { part.fileName }
                .getOrNull()
                ?.let { name -> runCatching { MimeUtility.decodeText(name) }.getOrDefault(name) }
                ?: (part as? MimeBodyPart)?.let { runCatching { it.contentID }.getOrNull() }

        val sanitised =
            raw
                ?.substringAfterLast('/')
                ?.substringAfterLast('\\')
                ?.replace(UNSAFE_FILENAME_CHARS, "_")
                ?.trim()
                ?.trim('.')
                ?.take(MAX_FILENAME_LENGTH)

        return sanitised?.takeIf { it.isNotBlank() } ?: "attachment-${index + 1}"
    }

    private fun senderAddress(message: Message): String? =
        runCatching { (message.from?.firstOrNull() as? InternetAddress)?.address }
            .getOrNull()
            ?: runCatching { message.from?.firstOrNull()?.toString() }.getOrNull()

    private fun senderName(message: Message): String? =
        runCatching { (message.from?.firstOrNull() as? InternetAddress)?.personal }.getOrNull()

    private fun addresses(
        message: Message,
        type: Message.RecipientType,
    ): List<String> =
        runCatching {
            message
                .getRecipients(type)
                ?.mapNotNull { (it as? InternetAddress)?.address ?: it?.toString() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    private class CollectedParts {
        var plainBody: String? = null
        var htmlBody: String? = null
        var preferHtml: Boolean = false
        var attachmentBytes: Int = 0
        val attachments: MutableList<MailAttachment> = mutableListOf()

        /**
         * Picks the body to hand to the process.
         *
         * HTML wins over plain text when the two are `multipart/alternative` siblings, since
         * there they are the same content and the HTML is what the sender actually wrote.
         * Anywhere else plain text wins: siblings outside an `alternative` are separate
         * content, and the HTML is as likely to be a signature or a disclaimer as the
         * message. An empty mail still yields a body resource, so downstream expressions
         * never have to null-check it.
         */
        fun body(): Body {
            val html = htmlBody?.takeIf { it.isNotBlank() }
            val plain = plainBody?.takeIf { it.isNotBlank() }
            return when {
                html != null && (preferHtml || plain == null) -> Body(html, isHtml = true)
                plain != null -> Body(plain, isHtml = false)
                html != null -> Body(html, isHtml = true)
                else -> Body("", isHtml = false)
            }
        }
    }

    private data class Body(
        val content: String,
        val isHtml: Boolean,
    )

    private companion object {
        private val logger = KotlinLogging.logger {}

        private val UNSAFE_FILENAME_CHARS = Regex("""[^A-Za-z0-9._\- ]""")
        private val MESSAGE_ID_PATTERN = Regex("""<[^<>\s]+>""")
        private val THREAD_HEADERS = listOf("References", "In-Reply-To")

        private const val MAX_MULTIPART_DEPTH = 10
        private const val MAX_FILENAME_LENGTH = 200
        private const val READ_BUFFER_SIZE = 8192
        private const val BYTES_PER_MB = 1_000_000
        private const val MAX_TOTAL_ATTACHMENT_BYTES = 25 * BYTES_PER_MB
        private const val MAX_BODY_BYTES = 10 * BYTES_PER_MB

        /** Well above what a real mail carries, low enough to stay a bound. */
        private const val MAX_ATTACHMENTS = 100
    }
}
