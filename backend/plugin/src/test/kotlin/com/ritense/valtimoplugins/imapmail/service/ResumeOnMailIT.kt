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

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.domain.PluginProcessLink
import com.ritense.plugin.service.PluginService
import com.ritense.plugin.web.rest.request.PluginProcessLinkCreateDto
import com.ritense.processlink.domain.ActivityTypeWithEventName
import com.ritense.processlink.service.ProcessLinkService
import com.ritense.valtimoplugins.imapmail.BaseIntegrationTest
import com.ritense.valtimoplugins.imapmail.repository.ProcessedMailRepository
import com.ritense.valtimoplugins.imapmail.service.MailboxPollingService.Companion.RESUME_ON_MAIL_ACTION
import com.ritense.valueresolver.ValueResolverFactory
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.operaton.bpm.engine.RepositoryService
import org.operaton.bpm.engine.RuntimeService
import org.operaton.bpm.engine.delegate.VariableScope
import org.operaton.bpm.model.bpmn.Bpmn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import java.util.Properties
import java.util.UUID
import java.util.function.Function

/**
 * The reported scenario end to end, minus the mail server: cases waiting at a receive task,
 * a fresh mail - not a reply - arriving, and the handler, the real engine and Valtimo's real
 * `pv:` resolver deciding which case continues.
 */
class ResumeOnMailIT : BaseIntegrationTest() {
    @Autowired
    lateinit var incomingMailHandler: IncomingMailHandler

    @Autowired
    lateinit var repositoryService: RepositoryService

    @Autowired
    lateinit var runtimeService: RuntimeService

    @Autowired
    lateinit var pluginService: PluginService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var processedMailRepository: ProcessedMailRepository

    @Autowired
    lateinit var processLinkService: ProcessLinkService

    private lateinit var processDefinitionId: String

    @BeforeEach
    fun deploy() {
        deploy(PROCESS_KEY)
    }

    /**
     * Deploys the waiting process under [key]. A saved link is copied onto every later
     * deployment of its key, so a test that saves one deploys under a key of its own.
     */
    private fun deploy(key: String) {
        val model =
            Bpmn
                .createExecutableProcess(key)
                .startEvent()
                .receiveTask(ACTIVITY_ID)
                .endEvent()
                .done()
        val deployment =
            repositoryService
                .createDeployment()
                .addModelInstance("$key.bpmn", model)
                .deploy()
        processDefinitionId =
            repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deployment.id)
                .singleResult()
                .id
    }

    @Test
    fun `should continue only the case of the applicant who sent a fresh mail`() {
        val jan = waitingCaseOf("jan-${UUID.randomUUID()}@example.com")
        val piet = waitingCaseOf("piet-${UUID.randomUUID()}@example.com")

        val handled = handle(mailFrom(jan.applicant), link("""{"senderContains":"pv:aanvragerEmail"}"""))

        assertThat(handled).isTrue()
        assertThat(isWaiting(jan.processInstanceId)).isFalse()
        assertThat(isWaiting(piet.processInstanceId)).isTrue()
    }

    @Test
    fun `should hand the mail to the case it continues`() {
        val applicant = "jan-${UUID.randomUUID()}@example.com"
        // A wait state after the receive task, so the instance and its variables are still there to read.
        val modelWithWaitAfter =
            Bpmn
                .createExecutableProcess(PROCESS_KEY)
                .startEvent()
                .receiveTask(ACTIVITY_ID)
                .receiveTask("after")
                .endEvent()
                .done()
        val deployment =
            repositoryService.createDeployment().addModelInstance("$PROCESS_KEY.bpmn", modelWithWaitAfter).deploy()
        processDefinitionId =
            repositoryService
                .createProcessDefinitionQuery()
                .deploymentId(deployment.id)
                .singleResult()
                .id
        val waiting = waitingCaseOf(applicant)

        handle(mailFrom(applicant), link("""{"senderContains":"pv:aanvragerEmail"}"""))

        assertThat(runtimeService.getVariable(waiting.processInstanceId, "mailSender")).isEqualTo(applicant)
        assertThat(runtimeService.getVariable(waiting.processInstanceId, "mailSubject")).isEqualTo("Nog een vraag")
    }

    @Test
    fun `should continue no case for a mail from somebody none of them is waiting for`() {
        val jan = waitingCaseOf("jan-${UUID.randomUUID()}@example.com")
        val piet = waitingCaseOf("piet-${UUID.randomUUID()}@example.com")

        val handled =
            handle(
                mailFrom("onbekend-${UUID.randomUUID()}@example.com"),
                link("""{"senderContains":"pv:aanvragerEmail"}"""),
            )

        assertThat(handled).isFalse()
        assertThat(isWaiting(jan.processInstanceId)).isTrue()
        assertThat(isWaiting(piet.processInstanceId)).isTrue()
    }

    @Test
    fun `should not continue a case for mail from an address that merely contains its applicant's`() {
        val unique = UUID.randomUUID()
        val an = waitingCaseOf("an-$unique@example.com")
        val other = waitingCaseOf("other-$unique@example.com")

        val handled = handle(mailFrom("jan-$unique@example.com"), link("""{"senderContains":"pv:aanvragerEmail"}"""))

        assertThat(handled).isFalse()
        assertThat(isWaiting(an.processInstanceId)).isTrue()
        assertThat(isWaiting(other.processInstanceId)).isTrue()
    }

    @Test
    fun `should keep the mail handled when a reference cannot be resolved for a waiting case`() {
        // `doc:` on a process with no case document throws inside Valtimo. That must cost only
        // that case its match - not mark the mail's own transaction for rollback, which would
        // undo the claim and bring the mail back on every poll.
        val jan = waitingCaseOf("jan-${UUID.randomUUID()}@example.com")
        val message = mailFrom(jan.applicant)
        val identity = "msgid:${message.getHeader("Message-ID").first()}"
        val configurationId = UUID.randomUUID().toString()

        val handled =
            incomingMailHandler.handle(
                message,
                identity,
                configurationId,
                listOf(link("""{"senderContains":"pv:aanvragerEmail","subjectContains":"doc:/zaaknummer"}""")),
            )

        assertThat(handled).isFalse()
        assertThat(isWaiting(jan.processInstanceId)).isTrue()
        assertThat(processedMailRepository.existsById("$configurationId|$identity")).isTrue()
    }

    @Test
    fun `should still continue the other cases when one waiting case cannot be resolved`() {
        // Two open cases of the same applicant; resolving the first throws. The second must
        // still continue, and the mail must stay handled.
        val applicant = "jan-${UUID.randomUUID()}@example.com"
        val broken =
            runtimeService.startProcessInstanceById(
                processDefinitionId,
                mapOf("aanvragerEmail" to applicant, FAIL_RESOLUTION to true),
            )
        val healthy = waitingCaseOf(applicant)
        val message = mailFrom(applicant)
        val identity = "msgid:${message.getHeader("Message-ID").first()}"
        val configurationId = UUID.randomUUID().toString()

        val handled =
            incomingMailHandler.handle(
                message,
                identity,
                configurationId,
                listOf(link("""{"senderContains":"$FAILING_PREFIX:aanvragerEmail"}""")),
            )

        assertThat(handled).isTrue()
        assertThat(isWaiting(healthy.processInstanceId)).isFalse()
        assertThat(isWaiting(broken.id)).isTrue()
        assertThat(processedMailRepository.existsById("$configurationId|$identity")).isTrue()
    }

    @Test
    fun `should continue a case through a saved link whose plain filter contains a colon`() {
        // A saved link, so Valtimo's own end-of-task listener runs the marker action too and
        // resolves its properties: 'Re: aanvraag' must not be read as prefix 'Re'.
        val link = savedLink("""{"subjectContains":"Re: aanvraag"}""")
        val jan = waitingCaseOf("jan-${UUID.randomUUID()}@example.com")

        val handled = handle(mailFrom(jan.applicant, subject = "Re: aanvraag"), link)

        assertThat(handled).isTrue()
        assertThat(isWaiting(jan.processInstanceId)).isFalse()
    }

    @Test
    fun `should continue a case through a saved link whose filter refers to the case`() {
        val link = savedLink("""{"senderContains":"pv:aanvragerEmail"}""")
        val jan = waitingCaseOf("jan-${UUID.randomUUID()}@example.com")
        val piet = waitingCaseOf("piet-${UUID.randomUUID()}@example.com")

        val handled = handle(mailFrom(jan.applicant), link)

        assertThat(handled).isTrue()
        assertThat(isWaiting(jan.processInstanceId)).isFalse()
        assertThat(isWaiting(piet.processInstanceId)).isTrue()
    }

    @Test
    fun `should continue no case when a plain filter cannot tell the waiting ones apart`() {
        val applicant = "jan-${UUID.randomUUID()}@example.com"
        val first = waitingCaseOf(applicant)
        val second = waitingCaseOf("piet-${UUID.randomUUID()}@example.com")

        val handled = handle(mailFrom(applicant), link("""{"senderContains":"@example.com"}"""))

        assertThat(handled).isFalse()
        assertThat(isWaiting(first.processInstanceId)).isTrue()
        assertThat(isWaiting(second.processInstanceId)).isTrue()
    }

    @Test
    fun `should continue no case with a plain filter when one waits on each of two versions`() {
        // Valtimo copies a link onto every new version, so each version's link sees one case.
        val key = "$PROCESS_KEY-${UUID.randomUUID()}"
        deploy(key)
        val onFirstVersion = waitingCaseOf("jan-${UUID.randomUUID()}@example.com")
        val firstVersionLink = link("""{"subjectContains":"Nog een vraag"}""")
        deploy(key)
        val onSecondVersion = waitingCaseOf("piet-${UUID.randomUUID()}@example.com")
        val secondVersionLink = link("""{"subjectContains":"Nog een vraag"}""")
        val message = mailFrom(onFirstVersion.applicant)

        val handled =
            incomingMailHandler.handle(
                message,
                "msgid:${message.getHeader("Message-ID").first()}",
                UUID.randomUUID().toString(),
                listOf(firstVersionLink, secondVersionLink),
            )

        assertThat(handled).isFalse()
        assertThat(isWaiting(onFirstVersion.processInstanceId)).isTrue()
        assertThat(isWaiting(onSecondVersion.processInstanceId)).isTrue()
    }

    @Test
    fun `should offer resume-on-mail on a receive task and an intermediate catch event only`() {
        fun offers(activityType: ActivityTypeWithEventName) =
            pluginService
                .getPluginDefinitionActionsByActivityType(activityType.value)
                .any { it.id.key == RESUME_ON_MAIL_ACTION && it.id.pluginDefinition.key == "imap-mail" }

        assertThat(offers(ActivityTypeWithEventName.RECEIVE_TASK_END)).isTrue()
        assertThat(offers(ActivityTypeWithEventName.INTERMEDIATE_CATCH_EVENT_END)).isTrue()
        assertThat(offers(ActivityTypeWithEventName.MESSAGE_START_EVENT_START)).isFalse()
    }

    private data class WaitingCase(
        val applicant: String,
        val processInstanceId: String,
    )

    private fun waitingCaseOf(applicant: String): WaitingCase {
        val instance =
            runtimeService.startProcessInstanceById(processDefinitionId, mapOf("aanvragerEmail" to applicant))
        return WaitingCase(applicant, instance.id)
    }

    private fun isWaiting(processInstanceId: String): Boolean =
        runtimeService
            .createExecutionQuery()
            .processInstanceId(processInstanceId)
            .activityId(ACTIVITY_ID)
            .count() > 0

    private fun handle(
        message: Message,
        link: PluginProcessLink,
    ): Boolean =
        incomingMailHandler.handle(
            message,
            "msgid:${message.getHeader("Message-ID").first()}",
            UUID.randomUUID().toString(),
            listOf(link),
        )

    /** A new mail: no In-Reply-To, no References. */
    private fun mailFrom(
        sender: String,
        subject: String = "Nog een vraag",
    ): Message =
        MimeMessage(Session.getInstance(Properties())).apply {
            setFrom(InternetAddress(sender))
            setRecipient(Message.RecipientType.TO, InternetAddress("zaakpost@example.org"))
            this.subject = subject
            setText("Ik heb nog een vraag over mijn aanvraag.", "UTF-8")
            saveChanges()
        }

    /**
     * A resume-on-mail link saved the way the admin UI saves one, against a real plugin
     * configuration. Unlike [link], Valtimo finds this one when the receive task ends.
     */
    private fun savedLink(actionProperties: String): PluginProcessLink {
        deploy("$PROCESS_KEY-${UUID.randomUUID()}")
        val configuration =
            pluginService.createPluginConfiguration(
                "imap-mail-it-${UUID.randomUUID()}",
                objectMapper.readTree(
                    """{"host":"localhost","username":"zaakpost@example.org","password":"secret"}""",
                ) as ObjectNode,
                "imap-mail",
            )
        return processLinkService.createProcessLink(
            PluginProcessLinkCreateDto(
                processDefinitionId = processDefinitionId,
                activityId = ACTIVITY_ID,
                pluginConfigurationId = configuration.id.id,
                pluginActionDefinitionKey = RESUME_ON_MAIL_ACTION,
                actionProperties = objectMapper.readTree(actionProperties) as ObjectNode,
                activityType = ActivityTypeWithEventName.RECEIVE_TASK_END,
            ),
            null,
        ) as PluginProcessLink
    }

    private fun link(actionProperties: String): PluginProcessLink =
        mock<PluginProcessLink>().also {
            whenever(it.pluginActionDefinitionKey).thenReturn(RESUME_ON_MAIL_ACTION)
            whenever(it.activityType).thenReturn(ActivityTypeWithEventName.RECEIVE_TASK_END)
            whenever(it.activityId).thenReturn(ACTIVITY_ID)
            whenever(it.processDefinitionId).thenReturn(processDefinitionId)
            whenever(it.actionProperties).thenReturn(objectMapper.readTree(actionProperties) as ObjectNode)
        }

    /**
     * A value resolver that throws for a process carrying [FAIL_RESOLUTION], and otherwise
     * reads the named process variable: one waiting case broken, the others not, under the
     * same filter.
     */
    @TestConfiguration
    class FailingResolverConfiguration {
        @Bean
        fun failingValueResolverFactory(): ValueResolverFactory =
            object : ValueResolverFactory {
                override fun supportedPrefix(): String = FAILING_PREFIX

                override fun createResolver(
                    processInstanceId: String,
                    variableScope: VariableScope,
                ): Function<String, Any?> =
                    Function { name ->
                        check(variableScope.getVariable(FAIL_RESOLUTION) != true) { "resolution fails for this case" }
                        variableScope.getVariable(name)
                    }

                override fun createResolver(documentId: String): Function<String, Any?> =
                    Function { error("not resolvable by document") }

                override fun createValidator(documentDefinitionName: String): Function<String, Unit> = Function { }
            }
    }

    private companion object {
        private const val PROCESS_KEY = "resume-on-mail-handler-it"
        private const val ACTIVITY_ID = "await-mail"
        private const val FAILING_PREFIX = "failing"
        private const val FAIL_RESOLUTION = "failResolution"
    }
}
