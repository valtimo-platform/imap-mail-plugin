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

package com.ritense.plugin.sandbox

import com.ritense.document.service.DocumentService
import com.ritense.resource.domain.MetadataType
import com.ritense.resource.domain.TemporaryResourceSubmittedEvent
import com.ritense.resource.service.TemporaryResourceStorageService
import com.ritense.valtimo.contract.annotation.ProcessBean
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.delegate.DelegateExecution
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Puts the mail body and its attachments on the case's Documents tab.
 *
 * The plugin deliberately leaves them in temporary resource storage and hands the process
 * nothing but resource ids - a 20 MB body has no business being a process variable. That
 * storage is a staging area, though: it is not the case file, nothing in the UI lists it, and
 * `TemporaryResourceStorageService` prunes it. The Documents tab lists a document's
 * `relatedFiles`, which is a different thing entirely.
 *
 * Bridging the two is a stock Valtimo mechanism rather than anything this sandbox invents.
 * Publishing [TemporaryResourceSubmittedEvent] makes `s3-resource`'s
 * `TemporaryResourceSubmittedToS3EventListener` copy the resource into S3, assign it to the
 * document as a related file, and drop the temporary copy. All this class does is decide
 * which resources are worth that treatment and where the document id comes from.
 *
 * Called from the execution listeners in `mail-intake-process.bpmn`, on the same elements
 * that copy the other `mail*` variables into the document.
 *
 * `@ProcessBean` is not decoration: Valtimo hands the process engine a whitelist of beans
 * rather than the whole application context, so without it those listeners fail at runtime
 * with "Cannot resolve identifier 'mailDocumentAttachments'".
 */
@ProcessBean
@Component("mailDocumentAttachments")
class MailDocumentAttachments(
    private val documentService: DocumentService,
    private val storageService: TemporaryResourceStorageService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    /**
     * Attaches the body and every attachment of the mail that just arrived on [execution].
     *
     * Both the intake and the reply path call this, and both read the same `mail*` variables:
     * the plugin sets them per delivery, so on a reply they describe the reply.
     */
    fun attach(execution: DelegateExecution) {
        val documentId = documentIdOf(execution) ?: return
        val definitionName = documentService.get(documentId.toString()).definitionId().name()

        resourceIdsOf(execution).forEach { resourceId ->
            // Gone means an earlier execution in this same delivery already took it; see
            // metadataOrNull. Nothing to do and nothing wrong.
            val metadata = metadataOrNull(resourceId) ?: return@forEach

            ensureExtension(resourceId, metadata)
            eventPublisher.publishEvent(
                TemporaryResourceSubmittedEvent(resourceId, documentId, definitionName),
            )
        }
    }

    /**
     * The resource's metadata, or `null` when it is no longer in temporary storage.
     *
     * One mail can resume more than one case: every execution whose remembered
     * `mailMessageId` the reply references is signalled, and this listener runs on each of
     * them. The first one wins - the S3 listener deletes the temporary copy once it has
     * moved it - so the rest find nothing.
     *
     * Checking rather than catching, because by the time
     * `TemporaryResourceStorageService` throws, the exception has already marked the
     * delivery's transaction rollback-only and the whole mail is lost, not just one
     * attachment.
     *
     * The consequence is that only the first of several resumed cases gets the file. Giving
     * each its own copy would mean duplicating the content in temporary storage before the
     * first publish, which is not worth it here: two cases remembering the same
     * `Message-ID` means the same mail created two cases, and `imap_mail_processed` makes
     * that impossible within one plugin configuration.
     */
    private fun metadataOrNull(resourceId: String): Map<String, Any>? =
        runCatching { storageService.getResourceMetadata(resourceId) }
            .onFailure { logger.debug { "Resource $resourceId is already attached to another case; skipping" } }
            .getOrNull()

    /**
     * Gives a file without a suffix one, because `S3Resource` validates `extension` as
     * not-blank and inbound mail is under no obligation to oblige.
     *
     * This is not hypothetical: the sandbox's `06-nested-multipart.eml` carries an attachment
     * named `../../../etc/passwd`, which the parser reduces to `passwd`. Without this the
     * whole delivery fails - and fails unrecoverably, for the same rollback-only reason as
     * above.
     *
     * `.bin` rather than something guessed from the content type: the name is attacker
     * supplied, and a wrong-but-plausible suffix is worse than an obviously generic one.
     */
    private fun ensureExtension(
        resourceId: String,
        metadata: Map<String, Any>,
    ) {
        val fileName = metadata[FILE_NAME] as? String ?: return
        if (fileName.substringAfterLast('.', "").isNotBlank()) return

        logger.info { "Attachment '$fileName' has no extension; storing it as '$fileName.bin'" }
        storageService.patchResourceMetaData(resourceId, mapOf(FILE_NAME to "$fileName.bin"))
    }

    /**
     * The body first, then the attachments in the order the MIME tree gave them.
     *
     * The body is included on purpose: without it the tab shows a case's attachments but not
     * the mail they arrived with, and for the majority of mail - which has no attachments at
     * all - the tab would be empty.
     */
    private fun resourceIdsOf(execution: DelegateExecution): List<String> =
        buildList {
            (execution.getVariable(BODY_RESOURCE_ID) as? String)?.let { add(it) }
            (execution.getVariable(ATTACHMENT_RESOURCE_IDS) as? Collection<*>)
                ?.filterIsInstance<String>()
                ?.let { addAll(it) }
        }

    /**
     * The business key is the document id for any process started through a process-document
     * link, which is how this process is started. A process instance without one is not a
     * case, so there is nothing to attach to.
     */
    private fun documentIdOf(execution: DelegateExecution): UUID? {
        val documentId =
            execution.processBusinessKey?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (documentId == null) {
            logger.warn {
                "No document id on process instance ${execution.processInstanceId}; " +
                    "mail resources are left in temporary storage"
            }
        }
        return documentId
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** Both set by `FetchedMail.toProcessVariables`. */
        private const val BODY_RESOURCE_ID = "mailBodyResourceId"
        private const val ATTACHMENT_RESOURCE_IDS = "mailAttachmentResourceIds"

        private val FILE_NAME = MetadataType.FILE_NAME.key
    }
}
