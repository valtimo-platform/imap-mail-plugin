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

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.authorization.annotation.RunWithoutAuthorization
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import com.ritense.valtimoplugins.imapmail.domain.ProcessedMail
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties
import com.ritense.valtimoplugins.imapmail.repository.ProcessedMailRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.mail.Message
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Handles one incoming mail in one transaction: claim it, parse it, start the process.
 *
 * A separate bean from [MailboxPollingService] rather than a method on it, because the
 * transaction and authorization advice are applied by a proxy — a call from the polling
 * loop to a method on its own instance would silently bypass both, and every mail would be
 * claimed and started outside a transaction.
 *
 * All three steps share one transaction, so a process that fails to start also releases the
 * claim and the mail is retried on the next poll. Committing the claim first would be the
 * other trade-off: no retry, and a mail lost to a transient failure stays lost.
 */
open class IncomingMailHandler(
    private val processedMailRepository: ProcessedMailRepository,
    private val mailMessageParser: MailMessageParser,
    private val mailProcessStarter: MailProcessStarter,
    private val objectMapper: ObjectMapper,
) {
    /**
     * Returns `true` when a process was started, `false` when nothing matched.
     *
     * Throws when the mail has already been handled — the primary key on
     * `imap_mail_processed` is what makes two nodes polling the same mailbox safe, and the
     * violation has to escape this method for the transaction to roll back cleanly. The
     * caller is expected to treat it as a duplicate rather than a failure.
     */
    @RunWithoutAuthorization
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    open fun handle(
        message: Message,
        identity: String,
        pluginConfigurationId: String,
        processLinks: List<PluginProcessLink>,
    ): Boolean {
        val key = claimKey(pluginConfigurationId, identity)

        if (processedMailRepository.existsById(key)) {
            logger.debug { "Mail '$identity' was already handled for configuration '$pluginConfigurationId'; skipping" }
            return false
        }

        // Flushed rather than left to commit so that a concurrent claim from another node
        // fails here, before the mail is parsed and a case is created.
        processedMailRepository.saveAndFlush(
            ProcessedMail(mailIdentity = key, pluginConfigurationId = pluginConfigurationId),
        )

        val mail = mailMessageParser.parse(message, identity)
        val matching = processLinks.filter { matches(it, mail) }

        if (matching.isEmpty()) {
            logger.debug { "Mail '$identity' from '${mail.sender}' matched no receive-mail process link" }
            return false
        }

        // A started instance for any link is enough to call the mail handled. The links are
        // independent: one may be a message start event that always fires, another an
        // intermediate catch event that only fires when something is waiting for it.
        val started = matching.map { mailProcessStarter.start(it, mail) }.any { it }

        if (started) {
            logger.info {
                "Started ${matching.size} process link(s) for mail '$identity' from '${mail.sender}' " +
                    "with subject '${mail.subject}'"
            }
        } else {
            logger.debug {
                "Mail '$identity' matched ${matching.size} process link(s), none of which had anything to start"
            }
        }
        return started
    }

    private fun matches(
        processLink: PluginProcessLink,
        mail: FetchedMail,
    ): Boolean {
        val properties = processLink.actionProperties ?: return true
        val filter =
            try {
                objectMapper.treeToValue(properties, ReceiveMailProperties::class.java)
            } catch (e: Exception) {
                logger.warn(e) {
                    "Could not read the filter of the receive-mail link on activity '${processLink.activityId}' of " +
                        "process definition '${processLink.processDefinitionId}'; treating it as match-all"
                }
                return true
            }
        return filter.matches(mail)
    }

    private fun claimKey(
        pluginConfigurationId: String,
        identity: String,
    ): String {
        // Scoped per configuration: the same mail can legitimately arrive in two different
        // mailboxes (both were in the To header), and each should get its own case.
        val key = "$pluginConfigurationId|$identity"
        if (key.length <= MAX_IDENTITY_LENGTH) return key

        // Truncating would map two long, distinct Message-IDs onto one key and silently drop
        // the second mail as a duplicate. A digest keeps them distinct.
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(key.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return "$pluginConfigurationId|sha256:$digest"
    }

    private companion object {
        private val logger = KotlinLogging.logger {}

        /** Matches the `mail_identity` column width. */
        private const val MAX_IDENTITY_LENGTH = 512
    }
}
