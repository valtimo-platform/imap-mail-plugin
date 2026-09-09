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

package com.ritense.valtimoplugins.imapmail.plugin

import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.AuthenticationMode
import com.ritense.valtimoplugins.imapmail.domain.MailProtocol
import com.ritense.valtimoplugins.imapmail.domain.PostProcessAction
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class ImapMailPluginTest : BaseTest() {
    @Test
    fun `should apply defaults for every optional property`() {
        val connection = plugin().connectionProperties()

        assertThat(connection.protocol).isEqualTo(MailProtocol.IMAPS)
        assertThat(connection.port).isEqualTo(993)
        assertThat(connection.folder).isEqualTo("INBOX")
        assertThat(connection.authentication).isEqualTo(AuthenticationMode.BASIC)
        assertThat(connection.postProcessAction).isEqualTo(PostProcessAction.MARK_READ)
        assertThat(connection.maxMessagesPerPoll).isEqualTo(25)
        assertThat(connection.startTlsEnable).isTrue()
        assertThat(connection.debug).isFalse()
        assertThat(connection.oauth).isNull()
    }

    @Test
    fun `should default the port to the port of the configured protocol`() {
        // POP3 has no seen flag, so the default MARK_READ has to give way to DELETE.
        assertThat(plugin { asPop3("pop3s") }.connectionProperties().port).isEqualTo(995)
        assertThat(plugin { asPop3("pop3") }.connectionProperties().port).isEqualTo(110)
        assertThat(plugin { protocol = "imap" }.connectionProperties().port).isEqualTo(143)
    }

    @Test
    fun `should keep an explicit port over the protocol default`() {
        assertThat(plugin { port = 1993 }.connectionProperties().port).isEqualTo(1993)
    }

    @Test
    fun `should accept a protocol by scheme name or enum name, case insensitively`() {
        assertThat(plugin { protocol = "IMAPS" }.connectionProperties().protocol).isEqualTo(MailProtocol.IMAPS)
        assertThat(plugin { asPop3("POP3") }.connectionProperties().protocol).isEqualTo(MailProtocol.POP3)
    }

    @Test
    fun `should reject an unknown protocol`() {
        assertThatThrownBy { plugin { protocol = "exchange" }.connectionProperties() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Unsupported mail protocol 'exchange'")
    }

    @Test
    fun `should require a password for basic authentication`() {
        assertThatThrownBy { plugin { password = null }.connectionProperties() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("password is required when authentication is BASIC")
    }

    @Test
    fun `should require oauth settings for xoauth2 authentication`() {
        assertThatThrownBy { plugin { authentication = "XOAUTH2" }.connectionProperties() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("oauthTokenUrl")
    }

    @Test
    fun `should default the oauth scope to the exchange online resource`() {
        val connection = plugin { withOAuth() }.connectionProperties()

        assertThat(connection.authentication).isEqualTo(AuthenticationMode.XOAUTH2)
        assertThat(connection.oauth?.scope).isEqualTo("https://outlook.office365.com/.default")
    }

    @Test
    fun `should not require a password when using xoauth2`() {
        assertDoesNotThrow {
            plugin {
                withOAuth()
                password = null
            }.connectionProperties()
        }
    }

    @Test
    fun `should reject a plaintext oauth token url`() {
        assertThatThrownBy {
            plugin {
                withOAuth()
                oauthTokenUrl = "http://login.example.com/token"
            }.connectionProperties()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("oauthTokenUrl must use https")
    }

    @Test
    fun `should reject marking read on pop3 because it has no flags`() {
        assertThatThrownBy {
            plugin {
                protocol = "pop3s"
                postProcessAction = "MARK_READ"
            }.connectionProperties()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("does not support")
    }

    @Test
    fun `should allow deleting on pop3`() {
        val connection =
            plugin {
                protocol = "pop3s"
                postProcessAction = "DELETE"
            }.connectionProperties()

        assertThat(connection.postProcessAction).isEqualTo(PostProcessAction.DELETE)
    }

    @Test
    fun `should reject a non-inbox folder on pop3`() {
        assertThatThrownBy {
            plugin {
                protocol = "pop3s"
                postProcessAction = "DELETE"
                folder = "Archive"
            }.connectionProperties()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("exposes only the 'INBOX' folder")
    }

    @Test
    fun `should require a target folder when moving`() {
        assertThatThrownBy { plugin { postProcessAction = "MOVE" }.connectionProperties() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("targetFolder is required")
    }

    @Test
    fun `should reject moving a message into the folder it came from`() {
        assertThatThrownBy {
            plugin {
                postProcessAction = "MOVE"
                folder = "INBOX"
                targetFolder = "inbox"
            }.connectionProperties()
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("targetFolder must differ from folder")
    }

    @Test
    fun `should reject a non-positive message limit`() {
        assertThatThrownBy { plugin { maxMessagesPerPoll = 0 }.connectionProperties() }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("maxMessagesPerPoll must be positive")
    }

    @Test
    fun `should describe the mailbox without exposing the password`() {
        val connection = plugin().connectionProperties()

        assertThat(connection.describe()).isEqualTo("imaps://mailbox@mail.example.com:993/INBOX")
        assertThat(connection.describe()).doesNotContain(connection.password!!)
    }

    private fun plugin(configure: ImapMailPlugin.() -> Unit = {}): ImapMailPlugin =
        ImapMailPlugin().apply {
            host = "mail.example.com"
            username = "mailbox"
            password = "a-secret-password"
            configure()
        }

    /** POP3 cannot mark a message read, so a valid POP3 configuration must delete instead. */
    private fun ImapMailPlugin.asPop3(scheme: String) {
        protocol = scheme
        postProcessAction = "DELETE"
    }

    private fun ImapMailPlugin.withOAuth() {
        authentication = "XOAUTH2"
        oauthTokenUrl = "https://login.microsoftonline.com/tenant-id/oauth2/v2.0/token"
        oauthClientId = "client-id"
        oauthClientSecret = "client-secret"
    }
}
