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
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentAssociationService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Starts, or resumes, the process behind a `receive-mail` process link.
 *
 * Which of the two happens is decided by the BPMN element the link is attached to, not by
 * configuration:
 * - a message start event starts something new — a case (document process) for a normal
 *   process definition, or a bare process instance for a system process;
 * - a receive task or intermediate catch event resumes an instance that is already waiting,
 *   which is how a reply to an earlier mail lands back in the case that sent it — and only
 *   in that case, see [signalWaitingExecutions].
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
) {
    /**
     * Returns `true` when the link led to a started or resumed instance.
     *
     * A `false` means the link matched but had nothing to act on — typically an
     * intermediate catch event with no instance waiting at it, or a reply that belongs to no
     * open case. That is a normal state, not an error, so the caller counts it as skipped
     * rather than failed.
     */
    fun start(
        processLink: PluginProcessLink,
        mail: FetchedMail,
    ): Boolean {
        val variables = mail.toProcessVariables()
        return when (processLink.activityType) {
            ActivityTypeWithEventName.MESSAGE_START_EVENT_START -> startProcessByMessage(processLink, variables)
            else -> signalWaitingExecutions(processLink, mail, variables)
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

    private fun startSystemProcessByMessage(
        processLink: PluginProcessLink,
        variables: Map<String, Any>,
    ): Boolean {
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
            try {
                processDefinitionCaseDefinitionService
                    .findByProcessDefinitionId(ProcessDefinitionId(processLink.processDefinitionId))
                    ?: return false
            } catch (e: Exception) {
                logger.warn(e) {
                    "No case definition linked to process definition '${processLink.processDefinitionId}'"
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

        require(processDefinitionCaseDefinition.canInitializeDocument) {
            "Cannot start a case for process definition '${processLink.processDefinitionId}' because " +
                "canInitializeDocument is false on the linked case definition."
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
                // Valtimo resolves `doc:` for a process instance through the process-document
                // association, and falls back to the business key while that association does
                // not exist yet. It cannot exist yet here - it is created below, once the
                // instance has an id - so the start event's own listeners depend on this
                // business key to reach the document. Valtimo's own start path sets it the
                // same way.
                .processInstanceBusinessKey(document.id().toString())
                .setVariables(variables)
                .correlateStartMessage()

        AuthorizationContext.runWithoutAuthorization {
            processDocumentAssociationService.createProcessDocumentInstance(
                processInstance.id,
                UUID.fromString(document.id().toString()),
                repositoryService.getProcessDefinition(processLink.processDefinitionId).name,
            )
        }
        return true
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
    private fun signalWaitingExecutions(
        processLink: PluginProcessLink,
        mail: FetchedMail,
        variables: Map<String, Any>,
    ): Boolean {
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
            return false
        }

        val executions = waiting.filter { repliesTo(it, mail) }
        if (executions.isEmpty()) {
            logger.debug {
                "Mail '${mail.identity}' matched the filter on activity '${processLink.activityId}' but is not a " +
                    "reply to any of the ${waiting.size} execution(s) waiting there; leaving them untouched"
            }
            return false
        }

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
                    error("Unsupported activity type '${processLink.activityType}' for a receive-mail process link")
            }
        }
        return true
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
