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

import java.time.Instant

/**
 * One mail, read off the server and detached from the `jakarta.mail` session.
 *
 * Detaching matters: a `MimeMessage` is only readable while its folder is open, so the
 * poller parses everything it needs — including attachment bytes, which go straight into
 * temporary resource storage — before the connection is closed.
 *
 * Bodies and attachments are referenced by resource id rather than carried inline. Process
 * variables end up in the Operaton database, and a 20 MB mail body has no business being
 * there; the SMTP mail plugin reads its body from the same storage when sending a reply.
 */
data class FetchedMail(
    /** Stable per-message key used for duplicate detection. See `ImapMailClient.identityOf`. */
    val identity: String,
    val messageId: String?,
    val sender: String?,
    val senderName: String?,
    val recipients: List<String>,
    val ccRecipients: List<String>,
    val subject: String?,
    val receivedAt: Instant?,
    val sentAt: Instant?,
    /**
     * `Message-ID`s of the earlier mails in this thread, from `In-Reply-To` and `References`.
     *
     * This is how a reply finds its way back to the one case that is waiting for it: the
     * case remembers the `Message-ID` of the mail that started it, and a reply carries that
     * id in its `References` chain. See `MailProcessStarter.signalWaitingExecutions`.
     */
    val references: List<String>,
    /**
     * Resource id of the body to use when a process does not care which format it gets.
     *
     * Points at [bodyHtmlResourceId] or [bodyTextResourceId] — see `CollectedParts.body` for
     * which of the two wins. An empty mail has neither, and gets an empty text resource of
     * its own, so this is never null.
     */
    val bodyResourceId: String,
    val bodyIsHtml: Boolean,
    /** Resource id of the plain text body, when the mail carried a non-blank one. */
    val bodyTextResourceId: String?,
    /** Resource id of the HTML body, when the mail carried a non-blank one. */
    val bodyHtmlResourceId: String?,
    val attachments: List<MailAttachment>,
) {
    /**
     * Process variables handed to the started process instance.
     *
     * Prefixed with `mail` so they do not collide with variables a case process already
     * uses, and flat rather than nested because BPMN expressions and FormIO both deal
     * poorly with nested maps.
     */
    fun toProcessVariables(): Map<String, Any> =
        buildMap {
            put("mailIdentity", identity)
            put("mailBodyResourceId", bodyResourceId)
            put("mailBodyIsHtml", bodyIsHtml)
            // Left unset rather than set to null when the mail had no such part: an unset
            // variable is what a BPMN expression can test for, where a null one throws.
            bodyTextResourceId?.let { put("mailBodyTextResourceId", it) }
            bodyHtmlResourceId?.let { put("mailBodyHtmlResourceId", it) }
            put("attachments", attachments)
            put("mailAttachmentResourceIds", attachments.map { it.resourceId })
            put("mailAttachmentCount", attachments.size)
            put("mailRecipients", recipients)
            put("mailCcRecipients", ccRecipients)
            put("mailReferences", references)
            messageId?.let { put(MESSAGE_ID_VARIABLE, it) }
            sender?.let { put("mailSender", it) }
            senderName?.let { put("mailSenderName", it) }
            subject?.let { put("mailSubject", it) }
            receivedAt?.let { put("mailReceivedAt", it.toString()) }
            sentAt?.let { put("mailSentAt", it.toString()) }
        }

    companion object {
        /**
         * Named here rather than spelled out twice, because reply correlation reads back the
         * very variable [toProcessVariables] writes — a rename in one place only would
         * silently stop every reply from finding its case.
         */
        const val MESSAGE_ID_VARIABLE = "mailMessageId"
    }
}

data class MailAttachment(
    val fileName: String,
    val contentType: String?,
    val sizeInBytes: Long,
    val resourceId: String,
)
