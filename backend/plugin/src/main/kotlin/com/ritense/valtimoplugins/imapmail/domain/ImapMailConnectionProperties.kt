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
 * Everything needed to open one mailbox, resolved from the plugin configuration.
 *
 * Kept separate from the plugin class so the client and the poller can be tested without
 * the plugin framework, and so [validate] can reject a combination the admin UI allowed
 * through — the frontend cannot know that, say, POP3 has no folders.
 */
data class ImapMailConnectionProperties(
    val host: String,
    val port: Int,
    val protocol: MailProtocol,
    val username: String,
    val password: String?,
    val authentication: AuthenticationMode,
    val oauth: OAuth2Properties?,
    val folder: String,
    val startTlsEnable: Boolean,
    val maxMessagesPerPoll: Int,
    val postProcessAction: PostProcessAction,
    val targetFolder: String?,
    val debug: Boolean,
) {
    /**
     * Rejects configurations that cannot work, with a message naming the offending field.
     *
     * Called once per poll rather than at save time on purpose: a plugin configuration can be
     * edited to an invalid state through the API, and failing the poll loudly beats silently
     * skipping the mailbox.
     */
    fun validate() {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..MAX_PORT) { "port must be between 1 and $MAX_PORT, was $port" }
        require(username.isNotBlank()) { "username must not be blank" }
        require(folder.isNotBlank()) { "folder must not be blank" }
        require(maxMessagesPerPoll > 0) { "maxMessagesPerPoll must be positive, was $maxMessagesPerPoll" }

        when (authentication) {
            AuthenticationMode.BASIC ->
                require(!password.isNullOrBlank()) {
                    "password is required when authentication is BASIC"
                }

            AuthenticationMode.XOAUTH2 ->
                requireNotNull(oauth) {
                    "oauthTokenUrl, oauthClientId and oauthClientSecret are required when authentication is XOAUTH2"
                }.validate()
        }

        if (postProcessAction.requiresFlags) {
            require(protocol.supportsFlags) {
                "postProcessAction $postProcessAction needs message flags, which ${protocol.schemeName} does " +
                    "not support. Use DELETE or NONE instead."
            }
        }
        if (postProcessAction.requiresFolders) {
            require(protocol.supportsFolders) {
                "postProcessAction $postProcessAction needs folders, which ${protocol.schemeName} does not support."
            }
            require(!targetFolder.isNullOrBlank()) {
                "targetFolder is required when postProcessAction is ${PostProcessAction.MOVE}"
            }
            require(!targetFolder.equals(folder, ignoreCase = true)) {
                "targetFolder must differ from folder, both are '$folder'"
            }
        }
        if (!protocol.supportsFolders) {
            require(folder.equals(POP3_FOLDER, ignoreCase = true)) {
                "${protocol.schemeName} exposes only the '$POP3_FOLDER' folder, was '$folder'"
            }
        }
    }

    /** Identifies the mailbox in logs without leaking the password. */
    fun describe(): String = "${protocol.schemeName}://$username@$host:$port/$folder"

    companion object {
        const val POP3_FOLDER = "INBOX"
        private const val MAX_PORT = 65535
    }
}

/**
 * OAuth2 client-credentials settings for [AuthenticationMode.XOAUTH2].
 *
 * The token endpoint is configured in full rather than assembled from a tenant id, so the
 * same plugin works against Microsoft 365, Google Workspace and any other provider that
 * issues bearer tokens usable as SASL XOAUTH2.
 */
data class OAuth2Properties(
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String,
    val scope: String,
) {
    fun validate() {
        require(tokenUrl.isNotBlank()) { "oauthTokenUrl must not be blank" }
        require(tokenUrl.startsWith("https://")) { "oauthTokenUrl must use https, was '$tokenUrl'" }
        require(clientId.isNotBlank()) { "oauthClientId must not be blank" }
        require(clientSecret.isNotBlank()) { "oauthClientSecret must not be blank" }
        require(scope.isNotBlank()) { "oauthScope must not be blank" }
    }

    /**
     * Cache key for an issued token. Deliberately includes the secret: a configuration that
     * was edited to a wrong or rotated secret must not keep riding on the token that the
     * previous secret obtained.
     */
    fun cacheKey(): String = "$tokenUrl|$clientId|$scope|${clientSecret.hashCode()}"
}
