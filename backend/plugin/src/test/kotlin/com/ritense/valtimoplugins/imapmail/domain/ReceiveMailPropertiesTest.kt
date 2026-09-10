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

package com.ritense.valtimoplugins.imapmail.domain

import com.ritense.valtimoplugins.imapmail.BaseTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ReceiveMailPropertiesTest : BaseTest() {
    @Test
    fun `should match everything when no filter is set`() {
        assertThat(ReceiveMailProperties().matches(mail())).isTrue()
    }

    @Test
    fun `should treat a blank filter as absent`() {
        assertThat(ReceiveMailProperties(senderContains = "  ", subjectContains = "").matches(mail())).isTrue()
    }

    @Test
    fun `should match a sender substring case insensitively`() {
        assertThat(ReceiveMailProperties(senderContains = "EXAMPLE.COM").matches(mail())).isTrue()
        assertThat(ReceiveMailProperties(senderContains = "example.org").matches(mail())).isFalse()
    }

    @Test
    fun `should match a subject substring case insensitively`() {
        assertThat(ReceiveMailProperties(subjectContains = "bezwaar").matches(mail())).isTrue()
        assertThat(ReceiveMailProperties(subjectContains = "factuur").matches(mail())).isFalse()
    }

    @Test
    fun `should match any of the To recipients`() {
        assertThat(ReceiveMailProperties(recipientContains = "balie").matches(mail())).isTrue()
        assertThat(ReceiveMailProperties(recipientContains = "onbekend").matches(mail())).isFalse()
    }

    /**
     * Pins the behaviour the field name does not suggest: `Cc` is parsed but never filtered
     * on, so a mailbox that receives on `Cc` matches no link. `07-other-recipient.eml` in
     * the sandbox is the same case end to end.
     */
    @Test
    fun `should ignore Cc recipients`() {
        val ccOnly =
            mail().copy(
                recipients = listOf("info@example.org"),
                ccRecipients = listOf("balie@example.org"),
            )

        assertThat(ReceiveMailProperties(recipientContains = "balie").matches(ccOnly)).isFalse()
        assertThat(ReceiveMailProperties(recipientContains = "info").matches(ccOnly)).isTrue()
    }

    @Test
    fun `should require every configured filter to match`() {
        assertThat(
            ReceiveMailProperties(senderContains = "jan", subjectContains = "Bezwaar", recipientContains = "balie")
                .matches(mail()),
        ).isTrue()

        assertThat(
            ReceiveMailProperties(senderContains = "jan", subjectContains = "factuur")
                .matches(mail()),
        ).isFalse()
    }

    @Test
    fun `should not match a filter against a mail that has no such field`() {
        val anonymous = mail().copy(sender = null, subject = null)

        assertThat(ReceiveMailProperties(senderContains = "jan").matches(anonymous)).isFalse()
        assertThat(ReceiveMailProperties(subjectContains = "bezwaar").matches(anonymous)).isFalse()
        assertThat(ReceiveMailProperties().matches(anonymous)).isTrue()
    }

    private fun mail() =
        FetchedMail(
            identity = "msgid:<1@example.com>",
            messageId = "<1@example.com>",
            sender = "jan@example.com",
            senderName = "Jan Jansen",
            recipients = listOf("gemeente@example.org", "balie@example.org"),
            ccRecipients = emptyList(),
            subject = "Bezwaar tegen besluit",
            receivedAt = null,
            sentAt = null,
            references = emptyList(),
            bodyResourceId = "resource-1",
            bodyIsHtml = false,
            attachments = emptyList(),
        )
}
