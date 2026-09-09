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

package com.ritense.valtimoplugins.imapmail.client

import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.AuthenticationMode
import com.ritense.valtimoplugins.imapmail.domain.ImapMailConnectionProperties
import com.ritense.valtimoplugins.imapmail.domain.MailProtocol
import com.ritense.valtimoplugins.imapmail.domain.OAuth2Properties
import com.ritense.valtimoplugins.imapmail.domain.PostProcessAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

class ImapMailClientTest : BaseTest() {
    private val client = ImapMailClient(mock())

    @Test
    fun `should enable implicit tls for imaps`() {
        val properties = client.session(connection()).properties

        assertThat(properties["mail.store.protocol"]).isEqualTo("imaps")
        assertThat(properties["mail.imaps.host"]).isEqualTo("mail.example.com")
        assertThat(properties["mail.imaps.port"]).isEqualTo("993")
        assertThat(properties["mail.imaps.ssl.enable"]).isEqualTo("true")
        assertThat(properties["mail.imaps.ssl.checkserveridentity"]).isEqualTo("true")
        assertThat(properties).doesNotContainKey("mail.imaps.starttls.enable")
    }

    @Test
    fun `should require starttls on a plaintext port rather than fall back to cleartext`() {
        val properties = client.session(connection(protocol = MailProtocol.IMAP, port = 143)).properties

        assertThat(properties["mail.imap.starttls.enable"]).isEqualTo("true")
        assertThat(properties["mail.imap.starttls.required"]).isEqualTo("true")
    }

    @Test
    fun `should not configure starttls when it is switched off`() {
        val connection =
            connection(
                protocol = MailProtocol.IMAP,
                port = 143,
                startTls = false,
            )

        assertThat(client.session(connection).properties).doesNotContainKey("mail.imap.starttls.enable")
    }

    @Test
    fun `should force xoauth2 and disable the password mechanisms`() {
        val properties = client.session(connection(authentication = AuthenticationMode.XOAUTH2)).properties

        assertThat(properties["mail.imaps.auth.mechanisms"]).isEqualTo("XOAUTH2")
        // Otherwise a server that rejects XOAUTH2 can make the client resend the bearer
        // token as a plain password.
        assertThat(properties["mail.imaps.auth.login.disable"]).isEqualTo("true")
        assertThat(properties["mail.imaps.auth.plain.disable"]).isEqualTo("true")
    }

    @Test
    fun `should not advertise xoauth2 for basic authentication`() {
        val properties = client.session(connection()).properties

        assertThat(properties).doesNotContainKey("mail.imaps.auth.mechanisms")
    }

    @Test
    fun `should keep the auth exchange out of the debug trace`() {
        val properties = client.session(connection(debug = true)).properties

        assertThat(properties["mail.debug.auth"]).isEqualTo("false")
    }

    @Test
    fun `should set connection and read timeouts so a hung server cannot stall the poll`() {
        val properties = client.session(connection()).properties

        assertThat(properties["mail.imaps.connectiontimeout"]).isEqualTo("30000")
        assertThat(properties["mail.imaps.timeout"]).isEqualTo("60000")
        assertThat(properties["mail.imaps.writetimeout"]).isEqualTo("60000")
    }

    @Test
    fun `should leave debug off unless it is switched on`() {
        assertThat(client.session(connection()).debug).isFalse()
        assertThat(client.session(connection(debug = true)).debug).isTrue()
    }

    @Test
    fun `should use pop3 property names for a pop3 connection`() {
        val properties =
            client
                .session(
                    connection(
                        protocol = MailProtocol.POP3S,
                        port = 995,
                        postProcessAction = PostProcessAction.DELETE,
                    ),
                ).properties

        assertThat(properties["mail.store.protocol"]).isEqualTo("pop3s")
        assertThat(properties["mail.pop3s.ssl.enable"]).isEqualTo("true")
    }

    private fun connection(
        protocol: MailProtocol = MailProtocol.IMAPS,
        port: Int = 993,
        authentication: AuthenticationMode = AuthenticationMode.BASIC,
        startTls: Boolean = true,
        debug: Boolean = false,
        postProcessAction: PostProcessAction = PostProcessAction.MARK_READ,
    ) = ImapMailConnectionProperties(
        host = "mail.example.com",
        port = port,
        protocol = protocol,
        username = "mailbox",
        password = "password".takeIf { authentication == AuthenticationMode.BASIC },
        authentication = authentication,
        oauth =
            OAuth2Properties(
                tokenUrl = "https://login.microsoftonline.com/tenant/oauth2/v2.0/token",
                clientId = "client-id",
                clientSecret = "client-secret",
                scope = "https://outlook.office365.com/.default",
            ).takeIf { authentication == AuthenticationMode.XOAUTH2 },
        folder = "INBOX",
        startTlsEnable = startTls,
        maxMessagesPerPoll = 25,
        postProcessAction = postProcessAction,
        targetFolder = null,
        debug = debug,
    )
}
