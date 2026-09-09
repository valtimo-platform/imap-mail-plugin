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
 * Mail store protocols this plugin can read a mailbox with.
 *
 * Both families are reached through the same `jakarta.mail.Store` abstraction, which is why
 * they live in one plugin. They are not equally capable, though: POP3 exposes a single
 * folder, has no server-side seen flag and cannot move a message. [supportsFlags] and
 * [supportsFolders] exist so the rest of the code can refuse an impossible configuration up
 * front instead of failing halfway through a poll.
 */
enum class MailProtocol(
    val schemeName: String,
    val defaultPort: Int,
    val implicitTls: Boolean,
    val supportsFlags: Boolean,
    val supportsFolders: Boolean,
) {
    IMAPS("imaps", 993, true, true, true),
    IMAP("imap", 143, false, true, true),
    POP3S("pop3s", 995, true, false, false),
    POP3("pop3", 110, false, false, false),
    ;

    companion object {
        fun fromValue(value: String?): MailProtocol =
            value
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    entries
                        .firstOrNull {
                            it.schemeName.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
                        }
                        ?: throw IllegalArgumentException(
                            "Unsupported mail protocol '$raw'. Supported: ${entries.joinToString { it.schemeName }}",
                        )
                }
                ?: IMAPS
    }
}
