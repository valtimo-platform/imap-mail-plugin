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

/**
 * Action properties of the `receive-mail` process link: an optional filter deciding which
 * of the fetched messages this particular link should start a process for.
 *
 * All filters are matched case-insensitively and all are AND-ed. A link with no filters at
 * all matches every message in the mailbox, which is the common single-process setup.
 *
 * [recipientContains] matches the `To` addresses only, not `Cc` - despite the name. A mail
 * that reaches the mailbox on `Cc`, or through an alias that never appears in `To`, is
 * fetched and claimed but matches no link. `07-other-recipient.eml` in the sandbox is that
 * case. Widening it to [FetchedMail.ccRecipients] would be a behaviour change, not a fix:
 * splitting one mailbox across processes by address relies on `Cc` not counting.
 */
data class ReceiveMailProperties(
    val senderContains: String? = null,
    val subjectContains: String? = null,
    val recipientContains: String? = null,
) {
    fun matches(mail: FetchedMail): Boolean =
        containedIn(senderContains, mail.sender) &&
            containedIn(subjectContains, mail.subject) &&
            (recipientContains.isNullOrBlank() || mail.recipients.any { containedIn(recipientContains, it) })

    private fun containedIn(
        needle: String?,
        haystack: String?,
    ): Boolean = needle.isNullOrBlank() || haystack?.contains(needle, ignoreCase = true) == true
}
