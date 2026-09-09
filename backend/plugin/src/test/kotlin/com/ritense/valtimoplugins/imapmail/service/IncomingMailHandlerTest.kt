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
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.FetchedMail
import com.ritense.valtimoplugins.imapmail.domain.ProcessedMail
import com.ritense.valtimoplugins.imapmail.repository.ProcessedMailRepository
import jakarta.mail.Message
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class IncomingMailHandlerTest : BaseTest() {
    private val objectMapper = ObjectMapper()

    private lateinit var processedMailRepository: ProcessedMailRepository
    private lateinit var mailMessageParser: MailMessageParser
    private lateinit var mailProcessStarter: MailProcessStarter
    private lateinit var handler: IncomingMailHandler
    private lateinit var message: Message

    @BeforeEach
    fun setUp() {
        processedMailRepository = mock()
        mailMessageParser = mock()
        mailProcessStarter = mock()
        message = mock()

        whenever(processedMailRepository.existsById(any())).thenReturn(false)
        whenever(mailMessageParser.parse(any(), any())).thenReturn(mail())
        whenever(mailProcessStarter.start(any(), any())).thenReturn(true)

        handler = IncomingMailHandler(processedMailRepository, mailMessageParser, mailProcessStarter, objectMapper)
    }

    @Test
    fun `should claim, parse and start for a new mail`() {
        val link = processLink()

        val handled = handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(link))

        assertThat(handled).isTrue()
        verify(mailProcessStarter).start(eq(link), any())

        val claim = argumentCaptor<ProcessedMail>()
        verify(processedMailRepository).saveAndFlush(claim.capture())
        assertThat(claim.firstValue.mailIdentity).isEqualTo("$CONFIGURATION_ID|$IDENTITY")
        assertThat(claim.firstValue.pluginConfigurationId).isEqualTo(CONFIGURATION_ID)
    }

    @Test
    fun `should scope the claim to the plugin configuration`() {
        // The same mail arriving in two mailboxes must yield two cases, so the claim key
        // cannot be the message identity on its own.
        handler.handle(message, IDENTITY, "configuration-a", listOf(processLink()))
        handler.handle(message, IDENTITY, "configuration-b", listOf(processLink()))

        val claims = argumentCaptor<ProcessedMail>()
        verify(processedMailRepository, times(2)).saveAndFlush(claims.capture())
        assertThat(claims.allValues.map { it.mailIdentity })
            .containsExactly("configuration-a|$IDENTITY", "configuration-b|$IDENTITY")
    }

    @Test
    fun `should skip a mail that was already handled without parsing it`() {
        whenever(processedMailRepository.existsById("$CONFIGURATION_ID|$IDENTITY")).thenReturn(true)

        val handled = handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(processLink()))

        assertThat(handled).isFalse()
        verify(mailMessageParser, never()).parse(any(), any())
        verify(mailProcessStarter, never()).start(any(), any())
        verify(processedMailRepository, never()).saveAndFlush(any())
    }

    @Test
    fun `should not start anything when no process link matches the filter`() {
        val link = processLink("""{"subjectContains":"factuur"}""")

        val handled = handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(link))

        assertThat(handled).isFalse()
        verify(mailProcessStarter, never()).start(any(), any())
    }

    @Test
    fun `should start only the process links whose filter matches`() {
        val matching = processLink("""{"senderContains":"jan@example.com"}""")
        val other = processLink("""{"senderContains":"piet@example.com"}""")

        val handled = handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(matching, other))

        assertThat(handled).isTrue()
        verify(mailProcessStarter).start(eq(matching), any())
        verify(mailProcessStarter, never()).start(eq(other), any())
    }

    @Test
    fun `should report not handled when every matching link had nothing to start`() {
        // An intermediate catch event with no waiting instance: matched, but nothing resumed.
        whenever(mailProcessStarter.start(any(), any())).thenReturn(false)

        val handled = handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(processLink()))

        assertThat(handled).isFalse()
    }

    @Test
    fun `should report handled when at least one of several links started`() {
        val first = processLink()
        val second = processLink()
        whenever(mailProcessStarter.start(eq(first), any())).thenReturn(false)
        whenever(mailProcessStarter.start(eq(second), any())).thenReturn(true)

        assertThat(handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(first, second))).isTrue()
    }

    @Test
    fun `should treat an unreadable filter as match-all rather than dropping the mail`() {
        val link = processLink("""{"senderContains":{"unexpected":"object"}}""")

        assertThat(handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(link))).isTrue()
    }

    @Test
    fun `should hash a claim key that would not fit the column`() {
        val longIdentity = "msgid:<${"x".repeat(600)}@example.com>"

        handler.handle(message, longIdentity, CONFIGURATION_ID, listOf(processLink()))

        val claim = argumentCaptor<ProcessedMail>()
        verify(processedMailRepository).saveAndFlush(claim.capture())
        assertThat(claim.firstValue.mailIdentity)
            .hasSizeLessThanOrEqualTo(512)
            .startsWith("$CONFIGURATION_ID|sha256:")
    }

    @Test
    fun `should pass the parsed mail on to the process starter`() {
        handler.handle(message, IDENTITY, CONFIGURATION_ID, listOf(processLink()))

        val started = argumentCaptor<FetchedMail>()
        verify(mailProcessStarter).start(any(), started.capture())
        assertThat(started.firstValue.sender).isEqualTo("jan@example.com")
        assertThat(started.firstValue.subject).isEqualTo("Bezwaar")
        assertThat(started.firstValue.bodyResourceId).isEqualTo("resource-1")
    }

    private fun processLink(actionProperties: String? = null): PluginProcessLink =
        mock<PluginProcessLink>().also { link ->
            whenever(link.actionProperties)
                .thenReturn(actionProperties?.let { objectMapper.readTree(it) as ObjectNode })
        }

    private fun mail() =
        FetchedMail(
            identity = IDENTITY,
            messageId = "<1@example.com>",
            sender = "jan@example.com",
            senderName = "Jan Jansen",
            recipients = listOf("gemeente@example.org"),
            ccRecipients = emptyList(),
            subject = "Bezwaar",
            receivedAt = null,
            sentAt = null,
            references = emptyList(),
            bodyResourceId = "resource-1",
            bodyIsHtml = false,
            attachments = emptyList(),
        )

    private companion object {
        private const val IDENTITY = "msgid:<1@example.com>"
        private const val CONFIGURATION_ID = "11111111-1111-1111-1111-111111111111"
    }
}
