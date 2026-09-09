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

import com.ritense.plugin.annotation.Plugin
import com.ritense.plugin.annotation.PluginAction
import com.ritense.plugin.annotation.PluginActionProperty
import com.ritense.plugin.annotation.PluginProperty
import com.ritense.processlink.domain.ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END
import com.ritense.processlink.domain.ActivityTypeWithEventName.MESSAGE_START_EVENT_START
import com.ritense.processlink.domain.ActivityTypeWithEventName.RECEIVE_TASK_END
import com.ritense.valtimoplugins.imapmail.domain.AuthenticationMode
import com.ritense.valtimoplugins.imapmail.domain.ImapMailConnectionProperties
import com.ritense.valtimoplugins.imapmail.domain.MailProtocol
import com.ritense.valtimoplugins.imapmail.domain.OAuth2Properties
import com.ritense.valtimoplugins.imapmail.domain.PostProcessAction
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Reads a mailbox over IMAP or POP3 and starts a process per message.
 *
 * The plugin has no outbound action: it deliberately does one direction only. Sending mail
 * is the SMTP mail plugin's job (`smtp-mail`), or the Microsoft Graph mail plugin's, and a
 * mail-processing flow normally uses this plugin to receive and one of those to reply.
 *
 * Note that the single action here does no work of its own. `receive-mail` is a marker: it
 * records on a BPMN element that this mailbox should start (or resume) that process, and
 * `MailboxPollingService` finds those markers when it polls. There is nothing to invoke
 * synchronously, because inbound mail arrives on the mail server's schedule, not the
 * process engine's.
 */
@Plugin(
    key = "imap-mail",
    title = "IMAP mail Plugin",
    description = "Read a mailbox over IMAP or POP3 and start a case per incoming e-mail",
)
open class ImapMailPlugin {
    @PluginProperty(key = "host", secret = false, required = true)
    lateinit var host: String

    @PluginProperty(key = "protocol", secret = false, required = false)
    var protocol: String? = null

    /** Defaults to the standard port of the configured protocol. */
    @PluginProperty(key = "port", secret = false, required = false)
    var port: Int? = null

    @PluginProperty(key = "username", secret = false, required = true)
    lateinit var username: String

    @PluginProperty(key = "password", secret = true, required = false)
    var password: String? = null

    @PluginProperty(key = "authentication", secret = false, required = false)
    var authentication: String? = null

    @PluginProperty(key = "oauthTokenUrl", secret = false, required = false)
    var oauthTokenUrl: String? = null

    @PluginProperty(key = "oauthClientId", secret = false, required = false)
    var oauthClientId: String? = null

    @PluginProperty(key = "oauthClientSecret", secret = true, required = false)
    var oauthClientSecret: String? = null

    @PluginProperty(key = "oauthScope", secret = false, required = false)
    var oauthScope: String? = null

    @PluginProperty(key = "folder", secret = false, required = false)
    var folder: String? = null

    @PluginProperty(key = "startTlsEnable", secret = false, required = false)
    var startTlsEnable: Boolean? = null

    @PluginProperty(key = "maxMessagesPerPoll", secret = false, required = false)
    var maxMessagesPerPoll: Int? = null

    @PluginProperty(key = "postProcessAction", secret = false, required = false)
    var postProcessAction: String? = null

    @PluginProperty(key = "targetFolder", secret = false, required = false)
    var targetFolder: String? = null

    // Off by default: enabling this writes the entire protocol dialogue - headers,
    // recipients and full message bodies - to the application log.
    @PluginProperty(key = "debug", secret = false, required = false)
    var debug: Boolean? = null

    /**
     * Marks a BPMN element as the entry point for mail from this mailbox.
     *
     * The properties are an optional filter, which is what makes one mailbox able to feed
     * several processes: link each process with a different filter and each sees only its
     * own mail. Leave them empty and the process gets everything.
     *
     * Supported on a message start event (starts a new case per mail) and on a receive task
     * or intermediate catch event (resumes a case waiting for a reply).
     */
    @PluginAction(
        key = "receive-mail",
        title = "Receive mail",
        description = "Start or continue a process for each e-mail read from this mailbox",
        activityTypes = [MESSAGE_START_EVENT_START, RECEIVE_TASK_END, INTERMEDIATE_CATCH_EVENT_END],
    )
    fun receiveMail(
        @PluginActionProperty senderContains: String?,
        @PluginActionProperty subjectContains: String?,
        @PluginActionProperty recipientContains: String?,
    ) {
        // Never invoked by the engine - see the class documentation. Logged rather than left
        // empty so that a call, which would mean the marker is wired up as something the
        // engine executes, is visible instead of silent.
        logger.debug {
            "receive-mail marker reached for filter (sender='$senderContains', subject='$subjectContains', " +
                "recipient='$recipientContains')"
        }
    }

    /**
     * Resolves the configuration into the value object the client and poller work with,
     * applying defaults.
     *
     * Defaults live here rather than in the frontend so that a configuration created
     * through the API behaves identically to one created in the admin UI.
     */
    fun connectionProperties(): ImapMailConnectionProperties {
        val resolvedProtocol = MailProtocol.fromValue(protocol)
        val resolvedAuthentication = AuthenticationMode.fromValue(authentication)

        return ImapMailConnectionProperties(
            host = host,
            port = port ?: resolvedProtocol.defaultPort,
            protocol = resolvedProtocol,
            username = username,
            password = password,
            authentication = resolvedAuthentication,
            oauth = oauthProperties(resolvedAuthentication),
            folder = folder?.takeIf { it.isNotBlank() } ?: DEFAULT_FOLDER,
            startTlsEnable = startTlsEnable ?: DEFAULT_START_TLS_ENABLE,
            maxMessagesPerPoll = maxMessagesPerPoll ?: DEFAULT_MAX_MESSAGES_PER_POLL,
            postProcessAction = PostProcessAction.fromValue(postProcessAction),
            targetFolder = targetFolder?.takeIf { it.isNotBlank() },
            debug = debug ?: DEFAULT_DEBUG,
        ).also { it.validate() }
    }

    private fun oauthProperties(mode: AuthenticationMode): OAuth2Properties? {
        if (mode != AuthenticationMode.XOAUTH2) return null

        return OAuth2Properties(
            tokenUrl = oauthTokenUrl.orEmpty(),
            clientId = oauthClientId.orEmpty(),
            clientSecret = oauthClientSecret.orEmpty(),
            scope = oauthScope?.takeIf { it.isNotBlank() } ?: DEFAULT_OAUTH_SCOPE,
        )
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        const val PLUGIN_KEY = "imap-mail"

        private const val DEFAULT_FOLDER = "INBOX"
        private const val DEFAULT_START_TLS_ENABLE = true
        private const val DEFAULT_MAX_MESSAGES_PER_POLL = 25
        private const val DEFAULT_DEBUG = false

        /**
         * Exchange Online's IMAP resource. Correct for the common Microsoft 365 case and
         * harmless elsewhere, since a provider that needs a different scope has to set one
         * anyway.
         */
        private const val DEFAULT_OAUTH_SCOPE = "https://outlook.office365.com/.default"
    }
}
