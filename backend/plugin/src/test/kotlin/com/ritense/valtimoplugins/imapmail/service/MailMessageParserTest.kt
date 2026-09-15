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

import com.ritense.resource.domain.MetadataType
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.MailRejectedException
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Properties

class MailMessageParserTest : BaseTest() {
    private lateinit var storageService: TemporaryResourceStorageService
    private lateinit var parser: MailMessageParser

    /** Resource id -> stored bytes, so assertions can look at what was actually written. */
    private lateinit var stored: MutableMap<String, ByteArray>
    private lateinit var metadata: MutableMap<String, Map<String, Any>>

    @BeforeEach
    fun setUp() {
        stored = mutableMapOf()
        metadata = mutableMapOf()
        storageService = mock()
        whenever(storageService.store(any<InputStream>(), any())).thenAnswer { invocation ->
            val bytes = invocation.getArgument<InputStream>(0).readAllBytes()
            val id = "resource-${stored.size + 1}"
            stored[id] = bytes
            @Suppress("UNCHECKED_CAST")
            metadata[id] = invocation.getArgument<Map<String, Any>>(1)
            id
        }
        parser = MailMessageParser(storageService)
    }

    @Test
    fun `should read sender, recipients and subject from a plain text mail`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: Jan Jansen <jan@example.com>
                    To: gemeente@example.org, balie@example.org
                    Cc: archief@example.org
                    Subject: Vraag over mijn aanvraag
                    Message-ID: <abc-123@example.com>
                    Content-Type: text/plain; charset=UTF-8

                    Wanneer krijg ik antwoord?
                    """.trimIndent(),
                ),
                "msgid:<abc-123@example.com>",
            )

        assertThat(mail.sender).isEqualTo("jan@example.com")
        assertThat(mail.senderName).isEqualTo("Jan Jansen")
        assertThat(mail.recipients).containsExactly("gemeente@example.org", "balie@example.org")
        assertThat(mail.ccRecipients).containsExactly("archief@example.org")
        assertThat(mail.subject).isEqualTo("Vraag over mijn aanvraag")
        assertThat(mail.messageId).isEqualTo("<abc-123@example.com>")
        assertThat(mail.bodyIsHtml).isFalse()
        assertThat(bodyOf(mail.bodyResourceId)).isEqualTo("Wanneer krijg ik antwoord?")
    }

    @Test
    fun `should prefer the html part of a multipart alternative mail`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Beide formaten
                    Content-Type: multipart/alternative; boundary="b1"

                    --b1
                    Content-Type: text/plain; charset=UTF-8

                    platte tekst
                    --b1
                    Content-Type: text/html; charset=UTF-8

                    <p>rijke tekst</p>
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.bodyIsHtml).isTrue()
        assertThat(bodyOf(mail.bodyResourceId)).contains("<p>rijke tekst</p>")
        assertThat(metadata[mail.bodyResourceId]?.get(MetadataType.CONTENT_TYPE.key)).isEqualTo("text/html")
        assertThat(metadata[mail.bodyResourceId]?.get(MetadataType.FILE_NAME.key)).isEqualTo("mail-body.html")
    }

    @Test
    fun `should store both formats when the mail carries both`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Beide formaten
                    Content-Type: multipart/alternative; boundary="b1"

                    --b1
                    Content-Type: text/plain; charset=UTF-8

                    platte tekst
                    --b1
                    Content-Type: text/html; charset=UTF-8

                    <p>rijke tekst</p>
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(bodyOf(mail.bodyTextResourceId!!)).contains("platte tekst")
        assertThat(metadata[mail.bodyTextResourceId]?.get(MetadataType.CONTENT_TYPE.key)).isEqualTo("text/plain")
        assertThat(metadata[mail.bodyTextResourceId]?.get(MetadataType.FILE_NAME.key)).isEqualTo("mail-body.txt")

        assertThat(bodyOf(mail.bodyHtmlResourceId!!)).contains("<p>rijke tekst</p>")
        assertThat(metadata[mail.bodyHtmlResourceId]?.get(MetadataType.CONTENT_TYPE.key)).isEqualTo("text/html")

        // The preferred body is one of the two, not a third copy of the same content.
        assertThat(mail.bodyResourceId).isEqualTo(mail.bodyHtmlResourceId)
        assertThat(stored).hasSize(2)

        assertThat(mail.toProcessVariables())
            .containsEntry("mailBodyTextResourceId", mail.bodyTextResourceId)
            .containsEntry("mailBodyHtmlResourceId", mail.bodyHtmlResourceId)
    }

    @Test
    fun `should leave the other format unset when the mail carries one`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Alleen html
                    Content-Type: text/html; charset=UTF-8

                    <p>alleen rijke tekst</p>
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.bodyIsHtml).isTrue()
        assertThat(mail.bodyResourceId).isEqualTo(mail.bodyHtmlResourceId)
        assertThat(mail.bodyTextResourceId).isNull()
        assertThat(mail.toProcessVariables()).doesNotContainKey("mailBodyTextResourceId")
    }

    @Test
    fun `should store an attachment separately from the body`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Met bijlage
                    Content-Type: multipart/mixed; boundary="b1"

                    --b1
                    Content-Type: text/plain; charset=UTF-8

                    zie bijlage
                    --b1
                    Content-Type: application/pdf; name="aanvraag.pdf"
                    Content-Disposition: attachment; filename="aanvraag.pdf"
                    Content-Transfer-Encoding: base64

                    ${base64("%PDF-1.4 fake pdf")}
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(bodyOf(mail.bodyResourceId)).isEqualTo("zie bijlage")
        assertThat(mail.attachments).hasSize(1)
        with(mail.attachments.single()) {
            assertThat(fileName).isEqualTo("aanvraag.pdf")
            assertThat(contentType).isEqualTo("application/pdf")
            assertThat(String(stored.getValue(resourceId), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4 fake pdf")
        }
    }

    @Test
    fun `should treat a part with a filename but no disposition as an attachment`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Bijlage zonder disposition
                    Content-Type: multipart/mixed; boundary="b1"

                    --b1
                    Content-Type: text/plain; charset=UTF-8

                    body
                    --b1
                    Content-Type: application/octet-stream; name="data.bin"

                    payload
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.attachments).extracting<String> { it.fileName }.containsExactly("data.bin")
    }

    @Test
    fun `should strip path structure out of an attachment filename`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Pad in bestandsnaam
                    Content-Type: multipart/mixed; boundary="b1"

                    --b1
                    Content-Type: application/pdf
                    Content-Disposition: attachment; filename="../../../etc/passwd"

                    x
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.attachments.single().fileName).isEqualTo("passwd")
    }

    @Test
    fun `should fall back to a positional name when the filename sanitises away`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Onbruikbare bestandsnaam
                    Content-Type: multipart/mixed; boundary="b1"

                    --b1
                    Content-Type: application/pdf
                    Content-Disposition: attachment; filename="///"

                    x
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.attachments.single().fileName).isEqualTo("attachment-1")
    }

    @Test
    fun `should reject a mail whose attachments exceed the size limit`() {
        // 26 MB of base64-encoded zeroes, one megabyte over the 25 MB cap.
        val oversized = ByteArray(26 * 1_000_000)
        assertThatThrownBy {
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Te groot
                    Content-Type: multipart/mixed; boundary="b1"

                    --b1
                    Content-Type: application/octet-stream
                    Content-Disposition: attachment; filename="big.bin"
                    Content-Transfer-Encoding: base64

                    ${Base64.getEncoder().encodeToString(oversized)}
                    --b1--
                    """.trimIndent(),
                ),
                "identity",
            )
            // Specifically a rejection, not just any failure: that type is what tells the
            // poller to post-process the mail instead of retrying it on every poll forever.
        }.isInstanceOf(MailRejectedException::class.java)
            .hasMessageContaining("exceed the maximum of 25 MB")
    }

    @Test
    fun `should reject a mail whose body exceeds the size limit`() {
        assertThatThrownBy {
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Te lang
                    Content-Type: text/plain; charset=UTF-8

                    ${"x".repeat(11 * 1_000_000)}
                    """.trimIndent(),
                ),
                "identity",
            )
        }.isInstanceOf(MailRejectedException::class.java)
            .hasMessageContaining("Mail body exceeds the maximum of 10 MB")
    }

    @Test
    fun `should reject a mail carrying more attachments than the limit allows`() {
        // A byte apiece, so the 25 MB cap never notices them. The count is the only thing
        // standing between this mail and 500 resources in storage and 500 ids in a process
        // variable.
        assertThatThrownBy {
            parser.parse(withAttachments(500), "identity")
        }.isInstanceOf(MailRejectedException::class.java)
            .hasMessageContaining("more than the maximum of 100 attachments")
    }

    @Test
    fun `should accept a mail sitting just under the attachment limit`() {
        val mail = parser.parse(withAttachments(100), "identity")

        assertThat(mail.attachments).hasSize(100)
    }

    @Test
    fun `should collect the thread references a reply carries`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Re: Aanvraag
                    In-Reply-To: <second@example.com>
                    References: <first@example.com> <second@example.com>
                    Content-Type: text/plain; charset=UTF-8

                    antwoord
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.references).containsExactly("<first@example.com>", "<second@example.com>")
        assertThat(mail.toProcessVariables()["mailReferences"])
            .isEqualTo(listOf("<first@example.com>", "<second@example.com>"))
    }

    @Test
    fun `should leave the references empty for a mail that starts a thread`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Aanvraag
                    Content-Type: text/plain; charset=UTF-8

                    nieuw
                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.references).isEmpty()
    }

    @Test
    fun `should decode a body using the charset it declares`() {
        // Latin-1 rather than UTF-8, so a parser that assumed one encoding would mangle it.
        val raw =
            """
            From: jan@example.com
            Subject: Accenten
            Content-Type: text/plain; charset=ISO-8859-1

            één café
            """.trimIndent()

        val mail =
            parser.parse(
                MimeMessage(
                    Session.getInstance(Properties()),
                    ByteArrayInputStream(raw.toByteArray(Charsets.ISO_8859_1)),
                ),
                "identity",
            )

        // Stored as UTF-8 regardless of what the mail declared, so consumers need not care.
        assertThat(bodyOf(mail.bodyResourceId)).isEqualTo("één café")
    }

    @Test
    fun `should still produce a body resource for an empty mail`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: jan@example.com
                    Subject: Leeg
                    Content-Type: text/plain; charset=UTF-8

                    """.trimIndent(),
                ),
                "identity",
            )

        assertThat(mail.bodyResourceId).isNotBlank()
        assertThat(bodyOf(mail.bodyResourceId)).isEmpty()
        assertThat(mail.bodyIsHtml).isFalse()
        assertThat(mail.bodyTextResourceId).isNull()
        assertThat(mail.bodyHtmlResourceId).isNull()
    }

    @Test
    fun `should expose the mail as flat process variables`() {
        val mail =
            parser.parse(
                message(
                    """
                    From: Jan Jansen <jan@example.com>
                    To: gemeente@example.org
                    Subject: Onderwerp
                    Message-ID: <xyz@example.com>
                    Content-Type: text/plain; charset=UTF-8

                    tekst
                    """.trimIndent(),
                ),
                "msgid:<xyz@example.com>",
            )

        val variables = mail.toProcessVariables()

        assertThat(variables)
            .containsEntry("mailSender", "jan@example.com")
            .containsEntry("mailSenderName", "Jan Jansen")
            .containsEntry("mailSubject", "Onderwerp")
            .containsEntry("mailIdentity", "msgid:<xyz@example.com>")
            .containsEntry("mailBodyIsHtml", false)
            .containsEntry("mailAttachmentCount", 0)
            .containsKey("mailBodyResourceId")
        assertThat(variables["mailRecipients"]).isEqualTo(listOf("gemeente@example.org"))
    }

    private fun bodyOf(resourceId: String): String = String(stored.getValue(resourceId), StandardCharsets.UTF_8)

    private fun base64(content: String): String =
        Base64.getEncoder().encodeToString(content.toByteArray(StandardCharsets.UTF_8))

    /**
     * A `multipart/mixed` mail carrying [count] one-byte attachments.
     *
     * Assembled rather than written as a raw literal: interpolating a multi-line value into a
     * `trimIndent` block leaves the surrounding lines indented, which silently turns the
     * headers into body text and yields a mail with no parts at all.
     */
    private fun withAttachments(count: Int): MimeMessage =
        message(
            buildString {
                append("From: jan@example.com\r\n")
                append("Subject: Bijlagen\r\n")
                append("Content-Type: multipart/mixed; boundary=\"b1\"\r\n\r\n")
                repeat(count) { index ->
                    append("--b1\r\n")
                    append("Content-Type: application/octet-stream\r\n")
                    append("Content-Disposition: attachment; filename=\"deel-${index + 1}.bin\"\r\n\r\n")
                    append("x\r\n")
                }
                append("--b1--\r\n")
            },
        )

    /** Parses a raw RFC 5322 message, so no mail server is needed. */
    private fun message(raw: String): MimeMessage =
        MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(raw.toByteArray(StandardCharsets.UTF_8)),
        )
}
