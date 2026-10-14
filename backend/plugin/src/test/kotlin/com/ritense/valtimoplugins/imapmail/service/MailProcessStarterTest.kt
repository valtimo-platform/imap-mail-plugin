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

import com.ritense.case.service.CaseDefinitionService
import com.ritense.case_.domain.definition.CaseDefinition
import com.ritense.document.domain.Document
import com.ritense.document.domain.impl.JsonSchemaDocumentId
import com.ritense.document.service.DocumentService
import com.ritense.document.service.result.CreateDocumentResult
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.domain.ProcessDefinitionCaseDefinition
import com.ritense.processdocument.domain.ProcessDefinitionCaseDefinitionId
import com.ritense.processdocument.domain.ProcessDefinitionId
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentAssociationService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.valtimo.contract.case_.CaseDefinitionId
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties
import com.ritense.valtimoplugins.imapmail.domain.ResolvedMailFilter
import com.ritense.valtimoplugins.imapmail.service.MailboxPollingService.Companion.RESUME_ON_MAIL_ACTION
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.repository.ProcessDefinition
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.engine.runtime.ExecutionQuery
import org.operaton.bpm.engine.runtime.MessageCorrelationBuilder
import org.operaton.bpm.engine.runtime.ProcessInstance
import org.operaton.bpm.model.bpmn.BpmnModelInstance
import org.operaton.bpm.model.bpmn.instance.CatchEvent
import org.operaton.bpm.model.bpmn.instance.Message
import org.operaton.bpm.model.bpmn.instance.MessageEventDefinition
import org.semver4j.Semver
import java.util.Optional
import java.util.UUID

/**
 * Covers the reply side of the starter: which of the instances parked at a receive task a
 * given mail may resume.
 *
 * The broadcast this guards against is not a hypothetical. The execution query is scoped to
 * a process definition and an activity, not to a case, so without correlation every case
 * waiting for its own reply would be handed the first reply that arrived for any of them -
 * body, attachments and sender included.
 */
class MailProcessStarterTest : BaseTest() {
    private lateinit var runtimeService: RuntimeService
    private lateinit var executionQuery: ExecutionQuery
    private lateinit var repositoryService: RepositoryService
    private lateinit var processPropertyService: ProcessPropertyService
    private lateinit var processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService
    private lateinit var documentService: DocumentService
    private lateinit var processDocumentAssociationService: ProcessDocumentAssociationService
    private lateinit var caseDefinitionService: CaseDefinitionService
    private lateinit var mailFilterResolver: MailFilterResolver
    private lateinit var starter: MailProcessStarter

    @BeforeEach
    fun setUp() {
        runtimeService = mock()
        executionQuery = mock()
        repositoryService = mock()
        processPropertyService = mock()
        processDefinitionCaseDefinitionService = mock()
        documentService = mock()
        processDocumentAssociationService = mock()
        caseDefinitionService = mock()
        mailFilterResolver = mock()

        whenever(runtimeService.createExecutionQuery()).thenReturn(executionQuery)
        whenever(executionQuery.processDefinitionId(any())).thenReturn(executionQuery)
        whenever(executionQuery.activityId(any())).thenReturn(executionQuery)
        whenever(executionQuery.processDefinitionKey(any())).thenReturn(executionQuery)
        val processDefinition = mock<ProcessDefinition>()
        whenever(processDefinition.key).thenReturn("mail-intake-process")
        whenever(repositoryService.getProcessDefinition("mail-intake-process:1:abc")).thenReturn(processDefinition)

        starter =
            MailProcessStarter(
                runtimeService,
                repositoryService,
                processPropertyService,
                processDefinitionCaseDefinitionService,
                documentService,
                processDocumentAssociationService,
                caseDefinitionService,
                mailFilterResolver,
            )
    }

    /**
     * The regression this guards against: starting the process by its definition key instead.
     *
     * A process may have more than one start event - a plain one for the start form and this
     * message start event for the mailbox - and Operaton enters a process started by key at
     * whichever it considers the initial activity, which is the plain one. The mail's own start
     * event then never runs, so neither do the execution listeners that put the mail on the
     * case, and the case is created empty.
     */
    @Test
    fun `should start a case by correlating the start message of the linked element`() {
        val link = messageStartEvent()
        val documentId = JsonSchemaDocumentId.existingId(UUID.fromString("11111111-2222-3333-4444-555555555555"))
        givenACaseCanBeStartedFor(link, documentId)
        val correlation = givenStartMessageCorrelates("IncomingMail", "process-instance-1")

        val started = starter.start(link, reply(references = emptyList()), ReceiveMailProperties())

        assertThat(started).isTrue()
        verify(correlation).processDefinitionId("Verwerk_email_handmatig:1:abc")
        // Valtimo resolves `doc:` through the business key until the association below exists,
        // so the start event's own listeners depend on it being the document id.
        verify(correlation).processInstanceBusinessKey(documentId.toString())
        verify(correlation).correlateStartMessage()
        verify(processDocumentAssociationService).createProcessDocumentInstance(
            eq("process-instance-1"),
            eq(UUID.fromString("11111111-2222-3333-4444-555555555555")),
            eq("Verwerk email handmatig"),
        )
    }

    @Test
    fun `should hand the mail on as process variables when it starts a case`() {
        val link = messageStartEvent()
        givenACaseCanBeStartedFor(
            link,
            JsonSchemaDocumentId.existingId(UUID.fromString("11111111-2222-3333-4444-555555555555")),
        )
        val correlation = givenStartMessageCorrelates("IncomingMail", "process-instance-1")
        val mail = reply(references = emptyList())

        starter.start(link, mail, ReceiveMailProperties())

        verify(correlation).setVariables(mail.toProcessVariables())
    }

    @Test
    fun `should resume only the case the reply belongs to`() {
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "<original-a@example.com>")
        awaits("instance-b", "<original-b@example.com>")

        val resumed =
            starter.start(
                receiveTask(),
                reply(references = listOf("<original-b@example.com>")),
                ReceiveMailProperties(),
            )

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
        verify(runtimeService, never()).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume nothing when the reply names no thread this mailbox is waiting on`() {
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "<original-a@example.com>")

        val resumed =
            starter.start(
                receiveTask(),
                reply(references = listOf("<somebody-else@example.com>")),
                ReceiveMailProperties(),
            )

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should resume nothing when the mail carries no thread headers at all`() {
        // A fresh mail that happens to match the link's filter is not a reply to anything.
        // Signalling on it is exactly the broadcast this correlation exists to prevent.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "<original-a@example.com>")
        awaits("instance-b", "<original-b@example.com>")

        val resumed = starter.start(receiveTask(), reply(references = emptyList()), ReceiveMailProperties())

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should follow the whole references chain, not only the immediate parent`() {
        // A long-running thread: the case still remembers the mail that started it, while the
        // latest reply names every message before it.
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "<first@example.com>")

        val resumed =
            starter.start(
                receiveTask(),
                reply(references = listOf("<first@example.com>", "<second@example.com>")),
                ReceiveMailProperties(),
            )

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume every case in the thread when one instance waits at the task twice`() {
        waiting("execution-a" to "instance-a", "execution-b" to "instance-a")
        awaits("instance-a", "<original-a@example.com>")

        val resumed =
            starter.start(
                receiveTask(),
                reply(references = listOf("<original-a@example.com>")),
                ReceiveMailProperties(),
            )

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
    }

    @Test
    fun `should hand the mail on as process variables when it does resume`() {
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "<original-a@example.com>")

        starter.start(receiveTask(), reply(references = listOf("<original-a@example.com>")), ReceiveMailProperties())

        verify(runtimeService).signal(
            eq("execution-a"),
            eq(reply(references = listOf("<original-a@example.com>")).toProcessVariables()),
        )
    }

    @Test
    fun `should report nothing resumed when no execution is waiting`() {
        waiting()

        val resumed =
            starter.start(
                receiveTask(),
                reply(references = listOf("<original-a@example.com>")),
                ReceiveMailProperties(),
            )

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should resume on mail only the case whose own filter the mail passes`() {
        // The same link filter, resolved per case: each case waits for its own applicant.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        val filter = ReceiveMailProperties(senderContains = "pv:aanvragerEmail")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(true)
        whenever(mailFilterResolver.resolve(filter, "execution-a"))
            .thenReturn(ResolvedMailFilter(ReceiveMailProperties(senderContains = "piet@example.com")))
        whenever(mailFilterResolver.resolve(filter, "execution-b"))
            .thenReturn(ResolvedMailFilter(ReceiveMailProperties(senderContains = "jan@example.com")))

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
        verify(runtimeService, never()).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume on mail without looking at thread headers`() {
        // The resume-on-mail counterpart of the reply correlation: a fresh mail is enough.
        waiting("execution-a" to "instance-a")
        val filter = ReceiveMailProperties(senderContains = "pv:aanvragerEmail")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(true)
        whenever(mailFilterResolver.resolve(filter, "execution-a"))
            .thenReturn(ResolvedMailFilter(ReceiveMailProperties(senderContains = "jan@example.com")))

        val resumed = starter.start(resumeOnMail(), reply(references = listOf("<unrelated@example.com>")), filter)

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
        verify(runtimeService, never()).getVariable(any(), any())
    }

    @Test
    fun `should resume on mail every case whose own filter the mail passes`() {
        // Two open cases of the same applicant: the mail is theirs in both, so both continue.
        // Resuming neither would lose it, since the mail is claimed either way.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        val filter = ReceiveMailProperties(senderContains = "pv:aanvragerEmail")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(true)
        whenever(mailFilterResolver.resolve(any(), any()))
            .thenReturn(ResolvedMailFilter(ReceiveMailProperties(senderContains = "jan@example.com")))

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
    }

    @Test
    fun `should not resume on mail a case whose filter value is missing`() {
        // The resolver returns null when a reference has no value; that must not mean match-all.
        waiting("execution-a" to "instance-a")
        val filter = ReceiveMailProperties(senderContains = "pv:aanvragerEmail")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(true)
        whenever(mailFilterResolver.resolve(filter, "execution-a")).thenReturn(null)

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should not resume on mail a case whose filter cannot be resolved`() {
        waiting("execution-a" to "instance-a")
        val filter = ReceiveMailProperties(subjectContains = "doc:/zaaknummer")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(true)
        whenever(mailFilterResolver.resolve(filter, "execution-a")).thenThrow(IllegalStateException("no document"))

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    @Test
    fun `should not resume on mail any case when a plain filter cannot tell several apart`() {
        // A plain-text filter is the same for every case; resuming all of them is the broadcast.
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        val filter = ReceiveMailProperties(senderContains = "jan@example.com")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(false)

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
        verify(mailFilterResolver, never()).resolve(any(), any())
    }

    @Test
    fun `should resume on mail the one waiting process with a plain filter`() {
        waiting("execution-a" to "instance-a")
        val filter = ReceiveMailProperties(senderContains = "jan@example.com")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(false)
        whenever(mailFilterResolver.resolve(filter, "execution-a")).thenReturn(ResolvedMailFilter(filter))

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should not resume on mail with a plain filter when another version of the process also waits`() {
        // One case on this version, one on an older one with a copy of the link: each link
        // would see only its own case and resume it.
        waiting("execution-a" to "instance-a")
        whenever(executionQuery.count()).thenReturn(2)
        val filter = ReceiveMailProperties(senderContains = "jan@example.com")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(false)

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
        verify(executionQuery).processDefinitionKey("mail-intake-process")
    }

    @Test
    fun `should not resume on mail the one waiting process when the plain filter does not match`() {
        waiting("execution-a" to "instance-a")
        val filter = ReceiveMailProperties(senderContains = "piet@example.com")
        whenever(mailFilterResolver.hasReferences(filter)).thenReturn(false)
        whenever(mailFilterResolver.resolve(filter, "execution-a")).thenReturn(ResolvedMailFilter(filter))

        val resumed = starter.start(resumeOnMail(), reply(references = emptyList()), filter)

        assertThat(resumed).isFalse()
        verify(runtimeService, never()).signal(any(), any<Map<String, Any>>())
    }

    private fun waiting(vararg executions: Pair<String, String>) {
        // Built before the stubbing rather than inside it: stubbing one mock while another
        // stubbing call is still open is what Mockito calls unfinished stubbing.
        val parked =
            executions.map { (executionId, processInstanceId) ->
                mock<Execution>().also {
                    whenever(it.id).thenReturn(executionId)
                    whenever(it.processInstanceId).thenReturn(processInstanceId)
                }
            }
        whenever(executionQuery.list()).thenReturn(parked)
        whenever(executionQuery.count()).thenReturn(parked.size.toLong())
    }

    /** Records which mail the case behind [processInstanceId] is waiting for a reply to. */
    private fun awaits(
        processInstanceId: String,
        messageId: String,
    ) {
        whenever(runtimeService.getVariable(processInstanceId, FetchedMail.MESSAGE_ID_VARIABLE))
            .thenReturn(messageId)
    }

    private fun messageStartEvent(): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.MESSAGE_START_EVENT_START)
            whenever(it.activityId).thenReturn("Event_0beitek")
            whenever(it.processDefinitionId).thenReturn("Verwerk_email_handmatig:1:abc")
        }

    /**
     * Everything between "this link points at a message start event" and "a case may be created
     * for it": the process is not a system process, it belongs to the active version of a case
     * definition that allows document initialisation, and the document itself is created.
     */
    private fun givenACaseCanBeStartedFor(
        link: PluginProcessLink,
        documentId: JsonSchemaDocumentId,
    ) {
        // Real objects rather than mocks: these are plain JPA entities whose `val`s Mockito
        // cannot stub, and building them is no more code than stubbing them would be.
        val caseDefinitionId = CaseDefinitionId("coworker", Semver("1.1.0"))
        val processDefinitionCaseDefinition =
            ProcessDefinitionCaseDefinition(
                ProcessDefinitionCaseDefinitionId(
                    ProcessDefinitionId(link.processDefinitionId),
                    caseDefinitionId,
                ),
                canInitializeDocument = true,
            )
        val caseDefinition =
            CaseDefinition(
                id = caseDefinitionId,
                name = "E-mailverwerking",
                createdDate = null,
                canHaveAssignee = true,
            )

        // Each mock is finished before the next stubbing starts; stubbing one inside another
        // `thenReturn(...)` is what Mockito reports as unfinished stubbing.
        val document = mock<Document>()
        whenever(document.id()).thenReturn(documentId)
        val createResult = mock<CreateDocumentResult>()
        doReturn(Optional.of(document)).whenever(createResult).resultingDocument()
        val processDefinition = mock<ProcessDefinition>()
        whenever(processDefinition.name).thenReturn("Verwerk email handmatig")

        whenever(processPropertyService.isSystemProcessById(link.processDefinitionId)).thenReturn(false)
        whenever(processDefinitionCaseDefinitionService.findByProcessDefinitionId(any()))
            .thenReturn(processDefinitionCaseDefinition)
        whenever(caseDefinitionService.getActiveCaseDefinition("coworker")).thenReturn(caseDefinition)
        whenever(documentService.createDocument(any())).thenReturn(createResult)
        whenever(repositoryService.getProcessDefinition(link.processDefinitionId)).thenReturn(processDefinition)
    }

    /** Stubs the BPMN lookup of the message name and the correlation builder chain. */
    private fun givenStartMessageCorrelates(
        messageName: String,
        processInstanceId: String,
    ): MessageCorrelationBuilder {
        val message = mock<Message>()
        whenever(message.name).thenReturn(messageName)
        val messageEventDefinition = mock<MessageEventDefinition>()
        whenever(messageEventDefinition.message).thenReturn(message)
        val catchEvent = mock<CatchEvent>()
        whenever(catchEvent.eventDefinitions).thenReturn(listOf(messageEventDefinition))
        val model = mock<BpmnModelInstance>()
        doReturn(catchEvent).whenever(model).getModelElementById<CatchEvent>(any())
        whenever(repositoryService.getBpmnModelInstance(any())).thenReturn(model)

        val processInstance = mock<ProcessInstance>()
        whenever(processInstance.id).thenReturn(processInstanceId)
        val correlation = mock<MessageCorrelationBuilder>()
        whenever(runtimeService.createMessageCorrelation(messageName)).thenReturn(correlation)
        whenever(correlation.processDefinitionId(any())).thenReturn(correlation)
        whenever(correlation.processInstanceBusinessKey(any())).thenReturn(correlation)
        whenever(correlation.setVariables(any())).thenReturn(correlation)
        whenever(correlation.correlateStartMessage()).thenReturn(processInstance)
        return correlation
    }

    private fun receiveTask(): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.RECEIVE_TASK_END)
            whenever(it.activityId).thenReturn("await-reply")
            whenever(it.processDefinitionId).thenReturn("mail-intake-process:1:abc")
        }

    private fun resumeOnMail(): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.pluginActionDefinitionKey).thenReturn(RESUME_ON_MAIL_ACTION)
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.RECEIVE_TASK_END)
            whenever(it.activityId).thenReturn("await-mail")
            whenever(it.processDefinitionId).thenReturn("mail-intake-process:1:abc")
        }

    private fun reply(references: List<String>) =
        FetchedMail(
            identity = "msgid:<reply@example.com>",
            messageId = "<reply@example.com>",
            sender = "jan@example.com",
            senderName = "Jan Jansen",
            recipients = listOf("zaakpost+antwoord@example.org"),
            ccRecipients = emptyList(),
            subject = "Re: Aanvraag",
            receivedAt = null,
            sentAt = null,
            references = references,
            bodyResourceId = "resource-1",
            bodyIsHtml = false,
            bodyTextResourceId = "resource-1",
            bodyHtmlResourceId = null,
            attachments = emptyList(),
        )
}
