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

import com.ritense.authorization.AuthorizationContext.Companion.runWithoutAuthorization
import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.plugin.service.PluginService
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimoplugins.imapmail.client.ImapMailClient
import com.ritense.valtimoplugins.imapmail.plugin.ImapMailPlugin
import com.ritense.valtimoplugins.imapmail.repository.ProcessedMailRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Drives the inbound side of the plugin: on a schedule, opens every mailbox that some BPMN
 * model is waiting on and feeds the mail it finds to [IncomingMailHandler].
 *
 * Which mailboxes those are is derived from the `receive-mail` process links rather than
 * from the plugin configurations. A configuration nobody links to is not polled at all,
 * which means adding a configuration for a mailbox you are not ready to process yet is
 * harmless, and removing the last link stops the polling without anyone having to remember
 * to delete credentials.
 */
open class MailboxPollingService(
    private val pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
    private val pluginService: PluginService,
    private val imapMailClient: ImapMailClient,
    private val incomingMailHandler: IncomingMailHandler,
    private val processedMailRepository: ProcessedMailRepository,
    private val retentionDays: Long,
) {
    /**
     * Guards against overlapping runs *within this JVM*.
     *
     * Spring's default scheduler is single-threaded, so today this cannot happen — but a
     * mailbox with a slow server can easily outlast a five-minute interval, and the day
     * someone configures a pool the overlap would double-fetch every message and rely
     * entirely on the claim table to sort it out.
     *
     * The `@SchedulerLock` below is the other half: this flag says nothing about the other
     * nodes of a cluster, all of which run the same cron against the same mailbox.
     */
    private val running = AtomicBoolean(false)

    /**
     * `lockAtMostFor` is the deadline for a node that dies mid-poll: until it passes, no
     * other node takes over. Generous relative to the default five-minute interval, because
     * the cost of releasing early — two nodes in the same mailbox — is worse than the cost of
     * a late release, which is a few skipped polls.
     */
    @Scheduled(cron = "\${valtimo.imap-mail.poll-cron:0 * * * * *}")
    @SchedulerLock(name = "imapMailPollMailboxes", lockAtLeastFor = "PT1S", lockAtMostFor = "PT10M")
    open fun pollMailboxes() {
        if (!running.compareAndSet(false, true)) {
            logger.info { "Skipping this mail poll: the previous one is still running" }
            return
        }
        try {
            pollAllConfigurations()
        } finally {
            running.set(false)
        }
    }

    private fun pollAllConfigurations() {
        val links = pluginProcessLinkRepository.findByPluginActionDefinitionKey(RECEIVE_MAIL_ACTION)

        // A process link may name a plugin configuration indirectly, through a reference
        // resolved at execution time from process variables. That cannot work here: there is
        // no execution to resolve against until a mail has already been fetched, which is
        // the very thing the configuration is needed for. Skip those loudly.
        val (bound, unbound) = links.partition { it.pluginConfigurationId != null }
        unbound.forEach {
            logger.warn {
                "Ignoring the receive-mail link on activity '${it.activityId}' of process definition " +
                    "'${it.processDefinitionId}': it has no fixed plugin configuration, and a mailbox cannot be " +
                    "resolved from process variables before the mail is read."
            }
        }

        val linksByConfiguration = bound.groupBy { requireNotNull(it.pluginConfigurationId) }

        if (linksByConfiguration.isEmpty()) {
            logger.debug { "No '$RECEIVE_MAIL_ACTION' process links configured; nothing to poll" }
            return
        }

        logger.debug { "Polling ${linksByConfiguration.size} mailbox configuration(s)" }
        linksByConfiguration.forEach { (configurationId, processLinks) ->
            // One mailbox failing must not stop the others: a single expired secret would
            // otherwise silently stall every other mailbox in the installation.
            try {
                pollConfiguration(configurationId, processLinks)
            } catch (e: Exception) {
                logger.error(e) { "Polling mailbox configuration '$configurationId' failed" }
            }
        }
    }

    internal fun pollConfiguration(
        configurationId: PluginConfigurationId,
        processLinks: List<PluginProcessLink>,
    ) {
        val plugin =
            pluginService.createInstance<ImapMailPlugin>(configurationId.id)
                ?: run {
                    logger.warn { "Plugin configuration '$configurationId' could not be instantiated; skipping" }
                    return
                }

        val connection = plugin.connectionProperties()
        val configurationKey = configurationId.id.toString()

        val result =
            imapMailClient.poll(connection) { message, identity ->
                try {
                    // Established here, outside the handler's transaction, even though
                    // IncomingMailHandler.handle is already @RunWithoutAuthorization.
                    //
                    // That annotation is not enough on its own: its aspect declares no order,
                    // so it runs inside the transaction advice and resets its thread-local
                    // before the commit. Valtimo's task listeners fire on AFTER_COMMIT, and
                    // they read the document to push an SSE update - a permission check that a
                    // scheduler thread, having no authenticated user, cannot pass. Wrapping the
                    // call keeps the context open across the commit. The thread-local is
                    // nesting-safe, so the inner annotation stays harmless.
                    runWithoutAuthorization {
                        incomingMailHandler.handle(message, identity, configurationKey, processLinks)
                    }
                } catch (e: DataIntegrityViolationException) {
                    // Another node claimed this mail first. Not an error, and not a reason to
                    // leave it on the server - the node that won the race is handling it.
                    logger.debug(e) { "Mail '$identity' was claimed concurrently; skipping" }
                    false
                }
            }

        if (result.fetched > 0) {
            logger.info {
                "Polled ${connection.describe()}: ${result.fetched} fetched, ${result.handled} started, " +
                    "${result.skipped} skipped, ${result.rejected} refused, ${result.failed} failed"
            }
        }
    }

    /**
     * Prunes claim rows that no mailbox can still produce.
     *
     * Without this the table grows for the lifetime of the installation. The retention
     * window has to outlast the only case where an old row still matters: a message whose
     * post-processing failed and which is therefore still sitting in the folder. Note that a
     * configuration using `PostProcessAction.NONE` never removes anything from the folder,
     * so for those the claim row is the *only* thing preventing a duplicate and pruning it
     * will re-process the mail — NONE is a debugging aid, not a production setting.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Scheduled(cron = "\${valtimo.imap-mail.retention-cron:0 30 3 * * *}")
    @SchedulerLock(name = "imapMailPruneProcessedMail", lockAtLeastFor = "PT5S", lockAtMostFor = "PT60M")
    open fun pruneProcessedMail() {
        val before = Instant.now().minus(retentionDays, ChronoUnit.DAYS)
        val deleted = processedMailRepository.deleteProcessedBefore(before)
        if (deleted > 0) {
            logger.info { "Pruned $deleted processed-mail row(s) older than $retentionDays day(s)" }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        const val RECEIVE_MAIL_ACTION = "receive-mail"
    }
}
