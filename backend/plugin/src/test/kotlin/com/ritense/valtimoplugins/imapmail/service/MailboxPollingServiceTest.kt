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

import com.ritense.plugin.domain.PluginConfigurationId
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.plugin.service.PluginService
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.plugin.ImapMailPlugin
import com.ritense.valtimoplugins.imapmail.service.MailboxPollingService.Companion.RECEIVE_MAIL_ACTION
import com.ritense.valtimoplugins.imapmail.service.MailboxPollingService.Companion.RESUME_ON_MAIL_ACTION
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

/**
 * Which mailboxes a poll opens. Only the discovery is covered here; what happens inside a
 * mailbox is the handler's and the starter's business.
 */
class MailboxPollingServiceTest : BaseTest() {
    private lateinit var pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository
    private lateinit var pluginService: PluginService
    private lateinit var poller: MailboxPollingService

    @BeforeEach
    fun setUp() {
        pluginProcessLinkRepository = mock()
        pluginService = mock()
        whenever(pluginProcessLinkRepository.findByPluginActionDefinitionKey(any())).thenReturn(emptyList())

        poller = MailboxPollingService(pluginProcessLinkRepository, pluginService, mock(), mock(), mock(), 90)
    }

    @Test
    fun `should poll a mailbox that only resume-on-mail links point at`() {
        val configurationId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val link = link(RESUME_ON_MAIL_ACTION, configurationId)
        whenever(pluginProcessLinkRepository.findByPluginActionDefinitionKey(RESUME_ON_MAIL_ACTION))
            .thenReturn(listOf(link))

        poller.pollMailboxes()

        // The configuration being instantiated is the poll opening that mailbox. It yields
        // null here, which ends the poll there.
        verify(pluginService).createInstance<ImapMailPlugin>(configurationId)
    }

    @Test
    fun `should poll a mailbox that receive-mail links point at`() {
        val configurationId = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val link = link(RECEIVE_MAIL_ACTION, configurationId)
        whenever(pluginProcessLinkRepository.findByPluginActionDefinitionKey(RECEIVE_MAIL_ACTION))
            .thenReturn(listOf(link))

        poller.pollMailboxes()

        verify(pluginService).createInstance<ImapMailPlugin>(configurationId)
    }

    @Test
    fun `should open no mailbox when no link points at one`() {
        poller.pollMailboxes()

        verify(pluginService, never()).createInstance<ImapMailPlugin>(any<UUID>())
    }

    private fun link(
        actionKey: String,
        configurationId: UUID,
    ): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.pluginActionDefinitionKey).thenReturn(actionKey)
            whenever(it.pluginConfigurationId).thenReturn(PluginConfigurationId(configurationId))
        }
}
