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

package com.ritense.valtimoplugins.imapmail.autoconfiguration

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.case.service.CaseDefinitionService
import com.ritense.plugin.service.PluginService
import com.ritense.processdocument.service.ProcessDefinitionCaseDefinitionService
import com.ritense.processdocument.service.ProcessDocumentService
import com.ritense.processlink.repository.ValtimoPluginProcessLinkRepository
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.config.LiquibaseMasterChangeLogLocation
import com.ritense.valtimo.service.ProcessPropertyService
import com.ritense.valtimoplugins.imapmail.client.ImapMailClient
import com.ritense.valtimoplugins.imapmail.client.OAuth2TokenClient
import com.ritense.valtimoplugins.imapmail.domain.ProcessedMail
import com.ritense.valtimoplugins.imapmail.plugin.ImapMailPluginFactory
import com.ritense.valtimoplugins.imapmail.repository.ProcessedMailRepository
import com.ritense.valtimoplugins.imapmail.service.IncomingMailHandler
import com.ritense.valtimoplugins.imapmail.service.MailMessageParser
import com.ritense.valtimoplugins.imapmail.service.MailProcessStarter
import com.ritense.valtimoplugins.imapmail.service.MailboxPollingService
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.domain.EntityScan
import org.springframework.context.annotation.Bean
import org.springframework.core.Ordered.HIGHEST_PRECEDENCE
import org.springframework.core.annotation.Order
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@AutoConfiguration
@EnableScheduling
@EnableJpaRepositories(basePackageClasses = [ProcessedMailRepository::class])
@EntityScan(basePackageClasses = [ProcessedMail::class])
class ImapMailAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(ImapMailPluginFactory::class)
    fun imapMailPluginFactory(pluginService: PluginService): ImapMailPluginFactory =
        ImapMailPluginFactory(pluginService)

    @Bean
    @ConditionalOnMissingBean(OAuth2TokenClient::class)
    fun imapMailOAuth2TokenClient(objectMapper: ObjectMapper): OAuth2TokenClient = OAuth2TokenClient(objectMapper)

    @Bean
    @ConditionalOnMissingBean(ImapMailClient::class)
    fun imapMailClient(tokenClient: OAuth2TokenClient): ImapMailClient = ImapMailClient(tokenClient)

    @Bean
    @ConditionalOnMissingBean(MailMessageParser::class)
    fun mailMessageParser(storageService: TemporaryResourceStorageService): MailMessageParser =
        MailMessageParser(storageService)

    @Bean
    @ConditionalOnMissingBean(MailProcessStarter::class)
    fun mailProcessStarter(
        runtimeService: RuntimeService,
        repositoryService: RepositoryService,
        processPropertyService: ProcessPropertyService,
        processDefinitionCaseDefinitionService: ProcessDefinitionCaseDefinitionService,
        processDocumentService: ProcessDocumentService,
        caseDefinitionService: CaseDefinitionService,
    ): MailProcessStarter =
        MailProcessStarter(
            runtimeService,
            repositoryService,
            processPropertyService,
            processDefinitionCaseDefinitionService,
            processDocumentService,
            caseDefinitionService,
        )

    @Bean
    @ConditionalOnMissingBean(IncomingMailHandler::class)
    fun incomingMailHandler(
        processedMailRepository: ProcessedMailRepository,
        mailMessageParser: MailMessageParser,
        mailProcessStarter: MailProcessStarter,
        objectMapper: ObjectMapper,
    ): IncomingMailHandler =
        IncomingMailHandler(
            processedMailRepository,
            mailMessageParser,
            mailProcessStarter,
            objectMapper,
        )

    /**
     * The poller is the only bean behind a switch.
     *
     * Turning it off leaves the plugin fully installed and configurable but stops it from
     * touching any mailbox — which is what you want on a node that should not compete for
     * mail (a migration runner, a local machine pointed at a shared test mailbox) and what
     * makes the plugin safe to deploy before the mailbox credentials exist.
     */
    @Bean
    @ConditionalOnMissingBean(MailboxPollingService::class)
    @ConditionalOnProperty(value = ["valtimo.imap-mail.polling-enabled"], matchIfMissing = true)
    fun mailboxPollingService(
        pluginProcessLinkRepository: ValtimoPluginProcessLinkRepository,
        pluginService: PluginService,
        imapMailClient: ImapMailClient,
        incomingMailHandler: IncomingMailHandler,
        processedMailRepository: ProcessedMailRepository,
        @Value("\${valtimo.imap-mail.retention-days:90}") retentionDays: Long,
    ): MailboxPollingService =
        MailboxPollingService(
            pluginProcessLinkRepository,
            pluginService,
            imapMailClient,
            incomingMailHandler,
            processedMailRepository,
            retentionDays,
        )

    @Order(HIGHEST_PRECEDENCE + 35)
    @Bean
    @ConditionalOnMissingBean(name = ["imapMailLiquibaseMasterChangeLogLocation"])
    fun imapMailLiquibaseMasterChangeLogLocation(): LiquibaseMasterChangeLogLocation =
        LiquibaseMasterChangeLogLocation("config/liquibase/imap-mail-master.xml")
}
