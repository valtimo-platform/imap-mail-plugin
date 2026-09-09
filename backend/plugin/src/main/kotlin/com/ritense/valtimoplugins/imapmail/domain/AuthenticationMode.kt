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
 * How the plugin authenticates against the mail store.
 *
 * [BASIC] is username + password. Exchange Online no longer accepts it for IMAP/POP3 —
 * Microsoft disabled Basic authentication for those protocols — so a Microsoft 365 mailbox
 * needs [XOAUTH2] with an app registration that has the `IMAP.AccessAsApp` (or delegated
 * `IMAP.AccessAsUser.All`) permission. On-premise Exchange, Dovecot and Zimbra generally
 * still accept [BASIC].
 */
enum class AuthenticationMode {
    BASIC,
    XOAUTH2,
    ;

    companion object {
        fun fromValue(value: String?): AuthenticationMode =
            value
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw IllegalArgumentException(
                            "Unsupported authentication mode '$raw'. Supported: ${entries.joinToString { it.name }}",
                        )
                }
                ?: BASIC
    }
}
