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

import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties.Field
import com.ritense.valtimoplugins.imapmail.domain.ResolvedMailFilter
import com.ritense.valueresolver.ValueResolverService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.ProcessEngine
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.interceptor.Command
import org.operaton.bpm.engine.impl.interceptor.CommandContext
import org.operaton.bpm.engine.impl.interceptor.CommandExecutor
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionEntity
import org.operaton.bpm.engine.impl.persistence.entity.ExecutionManager

class MailFilterResolverTest : BaseTest() {
    private lateinit var valueResolverService: ValueResolverService
    private lateinit var execution: ExecutionEntity
    private lateinit var resolver: MailFilterResolver

    @BeforeEach
    fun setUp() {
        valueResolverService = mock()
        whenever(valueResolverService.getValueResolvers()).thenReturn(listOf("pv", "doc", "case"))

        // The engine side, reduced to: run the command against a context that knows one execution.
        execution = mock()
        whenever(execution.processInstanceId).thenReturn("instance-a")
        val executionManager = mock<ExecutionManager>()
        whenever(executionManager.findExecutionById("execution-a")).thenReturn(execution)
        val commandContext = mock<CommandContext>()
        whenever(commandContext.executionManager).thenReturn(executionManager)
        val commandExecutor = mock<CommandExecutor>()
        whenever(commandExecutor.execute(any<Command<Any>>())).thenAnswer {
            it.getArgument<Command<Any>>(0).execute(commandContext)
        }
        val configuration = mock<ProcessEngineConfigurationImpl>()
        whenever(configuration.commandExecutorTxRequiresNew).thenReturn(commandExecutor)
        val processEngine = mock<ProcessEngine>()
        whenever(processEngine.processEngineConfiguration).thenReturn(configuration)

        resolver = MailFilterResolver(valueResolverService, processEngine)
    }

    @Test
    fun `should replace references with their value for the waiting execution`() {
        givenResolved("pv:aanvragerEmail" to "jan@example.com", "doc:/zaaknummer" to "Z-2026-001")

        val resolved =
            resolver.resolve(
                ReceiveMailProperties(
                    senderContains = "pv:aanvragerEmail",
                    subjectContains = "doc:/zaaknummer",
                    recipientContains = "zaakpost@",
                ),
                "execution-a",
            )

        assertThat(resolved).isEqualTo(
            ResolvedMailFilter(
                ReceiveMailProperties(
                    senderContains = "jan@example.com",
                    subjectContains = "Z-2026-001",
                    recipientContains = "zaakpost@",
                ),
                // Only the fields that held a reference; the typed recipient stays a substring.
                wholeValues = setOf(Field.SENDER, Field.SUBJECT),
            ),
        )
        verify(valueResolverService).resolveValues(
            eq("instance-a"),
            eq(execution),
            eq(listOf("pv:aanvragerEmail", "doc:/zaaknummer")),
        )
    }

    @Test
    fun `should leave text with a colon alone when its prefix is no resolver`() {
        val filter = ReceiveMailProperties(subjectContains = "Re: aanvraag")

        assertThat(resolver.hasReferences(filter)).isFalse()
        assertThat(resolver.resolve(filter, "execution-a")).isEqualTo(ResolvedMailFilter(filter))
        verify(valueResolverService, never()).resolveValues(any<String>(), any(), any())
    }

    @Test
    fun `should report references only for a resolver prefix`() {
        assertThat(resolver.hasReferences(ReceiveMailProperties(senderContains = "pv:aanvragerEmail"))).isTrue()
        assertThat(resolver.hasReferences(ReceiveMailProperties(senderContains = "jan@example.com"))).isFalse()
        assertThat(resolver.hasReferences(ReceiveMailProperties())).isFalse()
    }

    @Test
    fun `should give no filter when a reference has no value`() {
        // A blanked field would match every mail; a case without an applicant must match none.
        givenResolved("pv:aanvragerEmail" to null)

        assertThat(resolver.resolve(ReceiveMailProperties(senderContains = "pv:aanvragerEmail"), "execution-a"))
            .isNull()
    }

    @Test
    fun `should give no filter when a reference resolves to blank`() {
        givenResolved("pv:aanvragerEmail" to "  ")

        assertThat(resolver.resolve(ReceiveMailProperties(senderContains = "pv:aanvragerEmail"), "execution-a"))
            .isNull()
    }

    @Test
    fun `should trim a resolved value`() {
        // A value typed into a form with a stray space must still match the address.
        givenResolved("pv:aanvragerEmail" to " jan@example.com ")

        assertThat(resolver.resolve(ReceiveMailProperties(senderContains = "pv:aanvragerEmail"), "execution-a"))
            .isEqualTo(
                ResolvedMailFilter(ReceiveMailProperties(senderContains = "jan@example.com"), setOf(Field.SENDER)),
            )
    }

    @Test
    fun `should give no filter when the execution is gone`() {
        assertThat(resolver.resolve(ReceiveMailProperties(senderContains = "pv:aanvragerEmail"), "execution-x"))
            .isNull()
    }

    private fun givenResolved(vararg values: Pair<String, Any?>) {
        whenever(valueResolverService.resolveValues(eq("instance-a"), eq(execution), any()))
            .thenReturn(mapOf(*values))
    }
}
