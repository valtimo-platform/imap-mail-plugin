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
 * What happens to a message on the server once a process has been started for it.
 *
 * This is the plugin's first line of defence against handling the same mail twice: a message
 * that is marked seen, moved away or deleted is not picked up by the next poll at all. The
 * processed-mail table is the second line, for the window between "process started" and
 * "server acknowledged the flag".
 */
enum class PostProcessAction(
    val requiresFlags: Boolean,
    val requiresFolders: Boolean,
) {
    /** Set the `\Seen` flag. Unsupported on POP3. */
    MARK_READ(requiresFlags = true, requiresFolders = false),

    /** Copy to `targetFolder`, then flag as deleted in the source folder. Unsupported on POP3. */
    MOVE(requiresFlags = true, requiresFolders = true),

    /** Set the `\Deleted` flag and expunge. Works on POP3 too. */
    DELETE(requiresFlags = false, requiresFolders = false),

    /**
     * Leave the message untouched. Only safe in combination with the processed-mail table,
     * because every poll will keep seeing the message.
     */
    NONE(requiresFlags = false, requiresFolders = false),
    ;

    companion object {
        fun fromValue(value: String?): PostProcessAction =
            value
                ?.takeIf { it.isNotBlank() }
                ?.let { raw ->
                    entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                        ?: throw IllegalArgumentException(
                            "Unsupported post-process action '$raw'. Supported: ${entries.joinToString { it.name }}",
                        )
                }
                ?: MARK_READ
    }
}
