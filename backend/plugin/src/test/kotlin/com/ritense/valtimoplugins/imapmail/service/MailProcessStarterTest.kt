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
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentService
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.runtime.Execution
import org.operaton.bpm.engine.runtime.ExecutionQuery

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
    private lateinit var starter: MailProcessStarter

    @BeforeEach
    fun setUp() {
        runtimeService = mock()
        executionQuery = mock()

        whenever(runtimeService.createExecutionQuery()).thenReturn(executionQuery)
        whenever(executionQuery.processDefinitionId(any())).thenReturn(executionQuery)
        whenever(executionQuery.activityId(any())).thenReturn(executionQuery)

        starter =
            MailProcessStarter(
                runtimeService,
                mock<RepositoryService>(),
                mock<ProcessPropertyService>(),
                mock<ProcessDefinitionCaseDefinitionService>(),
                mock<ProcessDocumentService>(),
                mock<CaseDefinitionService>(),
            )
    }

    @Test
    fun `should resume only the case the reply belongs to`() {
        waiting("execution-a" to "instance-a", "execution-b" to "instance-b")
        awaits("instance-a", "<original-a@example.com>")
        awaits("instance-b", "<original-b@example.com>")

        val resumed = starter.start(receiveTask(), reply(references = listOf("<original-b@example.com>")))

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
        verify(runtimeService, never()).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume nothing when the reply names no thread this mailbox is waiting on`() {
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "<original-a@example.com>")

        val resumed = starter.start(receiveTask(), reply(references = listOf("<somebody-else@example.com>")))

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

        val resumed = starter.start(receiveTask(), reply(references = emptyList()))

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
            )

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
    }

    @Test
    fun `should resume every case in the thread when one instance waits at the task twice`() {
        waiting("execution-a" to "instance-a", "execution-b" to "instance-a")
        awaits("instance-a", "<original-a@example.com>")

        val resumed = starter.start(receiveTask(), reply(references = listOf("<original-a@example.com>")))

        assertThat(resumed).isTrue()
        verify(runtimeService).signal(eq("execution-a"), any<Map<String, Any>>())
        verify(runtimeService).signal(eq("execution-b"), any<Map<String, Any>>())
    }

    @Test
    fun `should hand the mail on as process variables when it does resume`() {
        waiting("execution-a" to "instance-a")
        awaits("instance-a", "<original-a@example.com>")

        starter.start(receiveTask(), reply(references = listOf("<original-a@example.com>")))

        verify(runtimeService).signal(
            eq("execution-a"),
            eq(reply(references = listOf("<original-a@example.com>")).toProcessVariables()),
        )
    }

    @Test
    fun `should report nothing resumed when no execution is waiting`() {
        waiting()

        val resumed = starter.start(receiveTask(), reply(references = listOf("<original-a@example.com>")))

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
    }

    /** Records which mail the case behind [processInstanceId] is waiting for a reply to. */
    private fun awaits(
        processInstanceId: String,
        messageId: String,
    ) {
        whenever(runtimeService.getVariable(processInstanceId, FetchedMail.MESSAGE_ID_VARIABLE))
            .thenReturn(messageId)
    }

    private fun receiveTask(): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.RECEIVE_TASK_END)
            whenever(it.activityId).thenReturn("await-reply")
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
