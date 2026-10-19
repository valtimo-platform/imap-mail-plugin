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

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.ritense.authorization.AuthorizationContext
import com.ritense.case.service.CaseDefinitionService
import com.ritense.document.domain.impl.request.NewDocumentRequest
import com.ritense.document.service.DocumentService
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.domain.ProcessDefinitionId
import com.ritense.processdocument.domain.impl.OperatonProcessInstanceId
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentAssociationService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties
import com.ritense.valtimoplugins.imapmail.service.MailboxPollingService.Companion.RESUME_ON_MAIL_ACTION
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Starts, or resumes, the process behind a `receive-mail` or `resume-on-mail` process link.
 *
 * Which of the two happens is decided by the BPMN element the link is attached to, not by
 * configuration:
 * - a message start event starts something new — a case (document process) for a normal
 *   process definition, or a bare process instance for a system process;
 * - a receive task or intermediate catch event resumes an instance that is already waiting,
 *   which is how a reply to an earlier mail lands back in the case that sent it — and only
 *   in that case, see [resumeReplies].
 *
 * A `resume-on-mail` link is the exception: it is always on a receive task or catch event,
 * and resumes the waiting instances whose own filter the mail passes, reply or not — see
 * [resumeMatching].
 *
 * Split out of the poller so that "which BPMN construct does this link mean" stays testable
 * without a mail server in the picture.
 */
@SkipComponentScan
@Service
class MailProcessStarter(
    private val runtimeService: RuntimeService,
    private val repositoryService: RepositoryService,
    private val processPropertyService: ProcessPropertyService,
    private val processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService,
    private val documentService: DocumentService,
    private val processDocumentAssociationService: ProcessDocumentAssociationService,
    private val caseDefinitionService: CaseDefinitionService,
    private val mailFilterResolver: MailFilterResolver,
) {
    /**
     * Returns `true` when the link led to a started or resumed instance.
     *
     * A `false` means the link matched but had nothing to act on — typically an
     * intermediate catch event with no instance waiting at it, or a reply that belongs to no
     * open case. That is a normal state, not an error, so the caller counts it as skipped
     * rather than failed.
     *
     * [filter] is the link's own filter. For `receive-mail` the caller has already applied
     * it; for `resume-on-mail` it can only be applied here, per waiting instance.
     */
    fun start(
        processLink: PluginProcessLink,
        mail: FetchedMail,
        filter: ReceiveMailProperties,
    ): Boolean {
        val variables = mail.toProcessVariables()
        return when {
            processLink.pluginActionDefinitionKey == RESUME_ON_MAIL_ACTION ->
                resumeMatching(processLink, mail, filter, variables)
            processLink.activityType == ActivityTypeWithEventName.MESSAGE_START_EVENT_START ->
                startProcessByMessage(processLink, variables)
            else -> resumeReplies(processLink, mail, variables)
        }
    }

    private fun startProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean =
        if (processPropertyService.isSystemProcessById(processLink.processDefinitionId)) {
            startSystemProcessByMessage(processLink, variables)
        } else {
            startDocumentProcessByMessage(processLink, variables)
        }

    /**
     * Starts only the latest version of a system process.
     *
     * Every edit of a system process deploys a new version, and Valtimo copies the process links
     * onto it while the old version keeps its own. Without this, one message would start one
     * instance per version ever deployed. Operaton itself only keeps message start subscriptions
     * for the latest version, so this is what a message start event means there too.
     *
     * Case processes need no such check: redeploying a draft detaches the previous definition
     * from its case definition, which [startDocumentProcessByMessage] already skips.
     */
    private fun startSystemProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean {
        if (!isLatestVersion(processLink.processDefinitionId)) {
            logger.debug {
                "Skipping process link for '${processLink.processDefinitionId}': a newer version of the process " +
                    "is deployed, and only the latest version is started by message"
            }
            return false
        }

        val messageName = messageNameOf(processLink)
        logger.info {
            "Starting system process by message '$messageName' for process definition '${processLink.processDefinitionId}'"
        }
        runtimeService
            .createMessageCorrelation(messageName)
            .processDefinitionId(processLink.processDefinitionId)
            .setVariables(variables)
            .correlateStartMessage()
        return true
    }

    private fun startDocumentProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean {
        val processDefinitionCaseDefinition =
            processDefinitionCaseDefinitionService
                .findByProcessDefinitionIdOrNull(ProcessDefinitionId(processLink.processDefinitionId))
                ?: run {
                    logger.debug {
                        "Skipping process link for '${processLink.processDefinitionId}': no case definition is " +
                            "linked to it, typically because it is an earlier, detached version of the process"
                    }
                    return false
                }

        // Only the deployed, active version of a case definition may be started. Without
        // this an old process link would keep creating cases against a superseded version.
        val activeCaseDefinition =
            caseDefinitionService.getActiveCaseDefinition(processDefinitionCaseDefinition.id.caseDefinitionId.key)
        if (activeCaseDefinition?.id != processDefinitionCaseDefinition.id.caseDefinitionId) {
            logger.debug {
                "Skipping process link for '${processLink.processDefinitionId}': it points at a case definition " +
                    "version that is no longer active"
            }
            return false
        }

        // Skipped rather than thrown. This is configuration and does not change between polls, so
        // an exception would roll back the claim and have every later poll fail on it again.
        if (!processDefinitionCaseDefinition.canInitializeDocument) {
            logger.warn {
                "Not starting a case for process definition '${processLink.processDefinitionId}': " +
                    "canInitializeDocument is false on the linked case definition"
            }
            return false
        }

        val messageName = messageNameOf(processLink)
        logger.info {
            "Creating a case for case definition '${activeCaseDefinition.id.key}' " +
                "(${activeCaseDefinition.id.versionTag}) by correlating start message '$messageName' to " +
                "'${processLink.activityId}'"
        }

        val newDocumentRequest =
            NewDocumentRequest(
                activeCaseDefinition.id.key,
                activeCaseDefinition.id.key,
                activeCaseDefinition.id.versionTag.toString(),
                JsonNodeFactory.instance.objectNode(),
            )
        val documentResult =
            AuthorizationContext.runWithoutAuthorization {
                documentService.createDocument(newDocumentRequest)
            }
        val document =
            documentResult.resultingDocument().orElse(null)
                ?: error("Failed to create a case for the incoming mail: ${documentResult.errors()}")

        // Correlated to the start message rather than started by process definition key.
        // ProcessDocumentService.newDocumentAndStartProcess, the obvious alternative, starts a
        // process by key, and Operaton then enters it at whichever start event it considers the
        // process's initial activity. A process with both a plain start event (someone fills in
        // the start form) and this message start event is a normal shape - the sandbox's
        // `Verwerk_email_handmatig` is one - and on that shape "by key" silently lands on the
        // plain one: the mail's own start event never runs, so neither do its execution
        // listeners, and the case is created without any of the mail on it.
        val processInstance =
            runtimeService
                .createMessageCorrelation(messageName)
                .processDefinitionId(processLink.processDefinitionId)
                // Valtimo resolves `doc:` for a process instance from its business key, and
                // associates the instance with that document when its start event runs. Both
                // depend on this being the document id. Valtimo's own start path sets it the
                // same way.
                .processInstanceBusinessKey(document.id().toString())
                .setVariables(variables)
                .correlateStartMessage()

        // Usually already associated by Valtimo's start event listener, and possibly with
        // another document: a building block's start listener points the association at its
        // own document. Creating one regardless would throw on that, so only fill the gap, the
        // same way Valtimo's own start paths do.
        AuthorizationContext.runWithoutAuthorization {
            if (processDocumentAssociationService
                    .findProcessDocumentInstance(OperatonProcessInstanceId(processInstance.id))
                    .isEmpty
            ) {
                processDocumentAssociationService.createProcessDocumentInstance(
                    processInstance.id,
                    UUID.fromString(document.id().toString()),
                    repositoryService.getProcessDefinition(processLink.processDefinitionId).name,
                )
            }
        }
        return true
    }

    private fun isLatestVersion(processDefinitionId: String): Boolean {
        val key = repositoryService.getProcessDefinition(processDefinitionId).key
        // A list rather than a single result: with tenants, there is a latest version per tenant.
        return repositoryService
            .createProcessDefinitionQuery()
            .processDefinitionKey(key)
            .latestVersion()
            .list()
            .any { it.id == processDefinitionId }
    }

    /**
     * Resumes the instances this mail is a reply to — and only those.
     *
     * The query finds every instance parked at the activity, across all cases, so it cannot
     * be the answer on its own: signalling all of them would file one citizen's reply, body
     * and attachments included, into every other case waiting at the same step. The thread
     * headers are what narrow it down, so a mail that carries none, or none that match, is
     * left alone rather than delivered to everybody.
     */
    private fun resumeReplies(
        processLink: PluginProcessLink,
        mail: FetchedMail,
        variables: Map<String, Any>,
    ): Boolean {
        val waiting = waitingExecutions(processLink)
        if (waiting.isEmpty()) return false

        val executions = waiting.filter { repliesTo(it, mail) }
        if (executions.isEmpty()) {
            logger.debug {
                "Mail '${mail.identity}' matched the filter on activity '${processLink.activityId}' but is not a " +
                    "reply to any of the ${waiting.size} execution(s) waiting there; leaving them untouched"
            }
            return false
        }

        resume(processLink, executions, variables)
        return true
    }

    /**
     * Resumes the instances whose own filter this mail passes, whether it is a reply or not.
     *
     * The same broadcast risk as [resumeReplies], with the filter instead of the thread doing
     * the narrowing: its references (`pv:`, `doc:`) are resolved per waiting instance, so
     * `senderContains = pv:aanvragerEmail` matches each case against its own applicant.
     *
     * A filter of plain text alone narrows nothing - it is the same for every instance - so
     * with more than one instance waiting it resumes none of them rather than all. With one,
     * it is unambiguous, which is the case for a system process that waits on a mailbox.
     * Counted over every version of the process: Valtimo copies the link onto each new
     * version, so a case on v1 and one on v2 would otherwise each look like the only one.
     */
    private fun resumeMatching(
        processLink: PluginProcessLink,
        mail: FetchedMail,
        filter: ReceiveMailProperties,
        variables: Map<String, Any>,
    ): Boolean {
        val waiting = waitingExecutions(processLink)
        if (waiting.isEmpty()) return false

        if (!mailFilterResolver.hasReferences(filter)) {
            val waitingOnEveryVersion = waitingOnEveryVersion(processLink)
            if (waitingOnEveryVersion > 1) {
                logger.warn {
                    "Not resuming any of the $waitingOnEveryVersion executions waiting at activity " +
                        "'${processLink.activityId}' across the versions of process definition " +
                        "'${processLink.processDefinitionId}' for mail '${mail.identity}': the resume-on-mail filter " +
                        "refers to no process or case value, so it cannot tell them apart"
                }
                return false
            }
        }

        val executions = waiting.filter { passesFilterOf(it, filter, mail) }
        if (executions.isEmpty()) {
            logger.debug {
                "Mail '${mail.identity}' passes the filter of none of the ${waiting.size} execution(s) waiting at " +
                    "activity '${processLink.activityId}'; leaving them untouched"
            }
            return false
        }

        resume(processLink, executions, variables)
        return true
    }

    private fun passesFilterOf(
        execution: Execution,
        filter: ReceiveMailProperties,
        mail: FetchedMail,
    ): Boolean {
        val resolved =
            try {
                mailFilterResolver.resolve(filter, execution.id)
            } catch (e: Exception) {
                logger.warn(e) {
                    "Could not resolve the resume-on-mail filter for execution '${execution.id}' of process " +
                        "instance '${execution.processInstanceId}'; not resuming it"
                }
                null
            }
        return resolved?.matches(mail) == true
    }

    /** How many executions wait at the link's activity in any version of its process. */
    private fun waitingOnEveryVersion(processLink: PluginProcessLink): Long {
        val key = repositoryService.getProcessDefinition(processLink.processDefinitionId).key
        return runtimeService
            .createExecutionQuery()
            .processDefinitionKey(key)
            .activityId(processLink.activityId)
            .count()
    }

    /** Every execution parked at the link's activity, across all cases. */
    private fun waitingExecutions(processLink: PluginProcessLink): List<Execution> {
        val waiting =
            runtimeService
                .createExecutionQuery()
                .processDefinitionId(processLink.processDefinitionId)
                .activityId(processLink.activityId)
                .list()

        if (waiting.isEmpty()) {
            logger.debug {
                "No execution waiting at activity '${processLink.activityId}' of process definition " +
                    "'${processLink.processDefinitionId}'"
            }
        }
        return waiting
    }

    private fun resume(
        processLink: PluginProcessLink,
        executions: List<Execution>,
        variables: Map<String, Any>,
    ) {
        executions.forEach { execution ->
            logger.info {
                "Resuming execution '${execution.id}' of process instance '${execution.processInstanceId}' " +
                    "at activity '${processLink.activityId}'"
            }
            when (processLink.activityType) {
                ActivityTypeWithEventName.RECEIVE_TASK_END ->
                    runtimeService.signal(execution.id, variables)

                ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END ->
                    runtimeService.messageEventReceived(messageNameOf(processLink), execution.id, variables)

                else ->
                    error("Unsupported activity type '${processLink.activityType}' for resuming on a mail")
            }
        }
    }

    /**
     * Decides whether this mail is a reply to the mail that instance is waiting on.
     *
     * The instance remembers the `Message-ID` of the last mail it received in
     * [FetchedMail.MESSAGE_ID_VARIABLE]; a reply repeats that id in its `References` chain,
     * which every mail client maintains. Matching the two is enough to route a reply back to
     * the one case that sent the mail being answered.
     */
    private fun repliesTo(
        execution: Execution,
        mail: FetchedMail,
    ): Boolean {
        if (mail.references.isEmpty()) return false

        val awaitedMessageId =
            runCatching {
                runtimeService.getVariable(execution.processInstanceId, FetchedMail.MESSAGE_ID_VARIABLE)
            }.getOrElse { e ->
                logger.warn(e) {
                    "Could not read '${FetchedMail.MESSAGE_ID_VARIABLE}' from process instance " +
                        "'${execution.processInstanceId}'; not treating this mail as a reply to it"
                }
                null
            }?.toString()

        return awaitedMessageId != null && awaitedMessageId in mail.references
    }

    private fun messageNameOf(processLink: PluginProcessLink): String {
        val model = repositoryService.getBpmnModelInstance(processLink.processDefinitionId)
        val element =
            model.getModelElementById<CatchEvent>(processLink.activityId)
                ?: error(
                    "No catch event '${processLink.activityId}' in process definition '${processLink.processDefinitionId}'",
                )
        return element.eventDefinitions
            .filterIsInstance<MessageEventDefinition>()
            .firstOrNull()
            ?.message
            ?.name
            ?: error(
                "No message event definition on element '${processLink.activityId}' in process definition " +
                    "'${processLink.processDefinitionId}'",
            )
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
