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
 * Action properties of the `receive-mail` and `resume-on-mail` process links: an optional
 * filter deciding which of the fetched messages this particular link should act on.
 *
 * On `resume-on-mail` the values may be references such as `pv:aanvragerEmail`; those are
 * replaced per waiting process by [com.ritense.valtimoplugins.imapmail.service.MailFilterResolver]
 * before [matches] runs. [matches] itself only ever sees plain text, but is told which fields
 * held a reference: those match as a whole value rather than as a substring, see [Field].
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
    fun matches(
        mail: FetchedMail,
        wholeValues: Set<Field> = emptySet(),
    ): Boolean =
        found(senderContains, mail.sender, Field.SENDER in wholeValues) &&
            found(subjectContains, mail.subject, Field.SUBJECT in wholeValues) &&
            (
                recipientContains.isNullOrBlank() ||
                    mail.recipients.any { found(recipientContains, it, Field.RECIPIENT in wholeValues) }
            )

    /**
     * A filter field. A field in `wholeValues` held a reference to the waiting process, and
     * its value must then appear as a whole value, not merely inside a longer one.
     *
     * Substring matching is right for text an administrator typed for every mail, and wrong
     * for a value that picks one case out of many: `pv:aanvragerEmail` resolving to `a@x.nl`
     * would otherwise also take mail from `ba@x.nl`, and case `Z-1` the mail about `Z-12` -
     * one person's mail filed into somebody else's case.
     */
    enum class Field { SENDER, SUBJECT, RECIPIENT }

    private fun found(
        needle: String?,
        haystack: String?,
        whole: Boolean,
    ): Boolean {
        if (needle.isNullOrBlank()) return true
        if (haystack == null) return false
        if (!whole) return haystack.contains(needle, ignoreCase = true)

        var from = haystack.indexOf(needle, ignoreCase = true)
        while (from >= 0) {
            val end = from + needle.length
            if (!joinsBefore(haystack, from) && !joinsAfter(haystack, end)) return true
            from = haystack.indexOf(needle, from + 1, ignoreCase = true)
        }
        return false
    }

    /** Whether the character before [index] would make the match part of a longer value. */
    private fun joinsBefore(
        text: String,
        index: Int,
    ): Boolean = index > 0 && text[index - 1].let { it.isLetterOrDigit() || it in VALUE_CHARACTERS || it == '.' }

    /**
     * Whether the character at [index] continues the value. A `.` only does when more of a
     * value follows it, so `jan@example.com.evil.nl` does not match `jan@example.com` but the
     * sentence `zaak Z-1.` does match `Z-1`.
     */
    private fun joinsAfter(
        text: String,
        index: Int,
    ): Boolean {
        if (index >= text.length) return false
        val next = text[index]
        if (next.isLetterOrDigit() || next in VALUE_CHARACTERS) return true
        return next == '.' && index + 1 < text.length && text[index + 1].isLetterOrDigit()
    }

    private companion object {
        /** Characters that occur inside e-mail addresses and identifiers such as case numbers. */
        private const val VALUE_CHARACTERS = "@_-+"
    }
}
