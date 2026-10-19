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

import com.ritense.valtimoplugins.imapmail.BaseIntegrationTest
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties.Field
import com.ritense.valtimoplugins.imapmail.domain.ResolvedMailFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.model.bpmn.Bpmn
import org.springframework.beans.factory.annotation.Autowired

/**
 * Runs [MailFilterResolver] against the real engine and Valtimo's real `pv:` resolver.
 *
 * The unit test stubs the command context; this is what shows that the waiting execution
 * really is a usable variable scope inside the command, local variables included.
 */
class MailFilterResolverIT : BaseIntegrationTest() {
    @Autowired
    lateinit var mailFilterResolver: MailFilterResolver

    @Autowired
    lateinit var repositoryService: RepositoryService

    @Autowired
    lateinit var runtimeService: RuntimeService

    @BeforeEach
    fun deploy() {
        val model =
            Bpmn
                .createExecutableProcess(PROCESS_KEY)
                .startEvent()
                .receiveTask("await-mail")
                .endEvent()
                .done()
        repositoryService
            .createDeployment()
            .addModelInstance("$PROCESS_KEY.bpmn", model)
            .deploy()
    }

    @Test
    fun `should resolve a process variable per waiting execution`() {
        val filter = ReceiveMailProperties(senderContains = "pv:aanvragerEmail", recipientContains = "zaakpost@")

        assertThat(mailFilterResolver.hasReferences(filter)).isTrue()
        assertThat(mailFilterResolver.resolve(filter, waitingFor("jan@example.com")))
            .isEqualTo(
                ResolvedMailFilter(
                    ReceiveMailProperties(senderContains = "jan@example.com", recipientContains = "zaakpost@"),
                    setOf(Field.SENDER),
                ),
            )
        assertThat(mailFilterResolver.resolve(filter, waitingFor("piet@example.com")))
            .isEqualTo(
                ResolvedMailFilter(
                    ReceiveMailProperties(senderContains = "piet@example.com", recipientContains = "zaakpost@"),
                    setOf(Field.SENDER),
                ),
            )
    }

    @Test
    fun `should give no filter when the waiting process lacks the variable`() {
        val instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY)
        val execution = waitingExecutionOf(instance.id)

        assertThat(mailFilterResolver.resolve(ReceiveMailProperties(senderContains = "pv:aanvragerEmail"), execution))
            .isNull()
    }

    /** Starts an instance with [applicant] as its `aanvragerEmail`; returns the waiting execution id. */
    private fun waitingFor(applicant: String): String {
        val instance = runtimeService.startProcessInstanceByKey(PROCESS_KEY, mapOf("aanvragerEmail" to applicant))
        return waitingExecutionOf(instance.id)
    }

    private fun waitingExecutionOf(processInstanceId: String): String =
        runtimeService
            .createExecutionQuery()
            .processInstanceId(processInstanceId)
            .activityId("await-mail")
            .singleResult()
            .id

    private companion object {
        private const val PROCESS_KEY = "resume-on-mail-it"
    }
}
