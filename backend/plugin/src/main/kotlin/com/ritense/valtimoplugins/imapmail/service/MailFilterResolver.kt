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

import com.ritense.valtimo.contract.annotation.SkipComponentScan
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties
import com.ritense.valtimoplugins.imapmail.domain.ReceiveMailProperties.Field
import com.ritense.valtimoplugins.imapmail.domain.ResolvedMailFilter
import com.ritense.valueresolver.ValueResolverService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.operaton.bpm.engine.ProcessEngine
import org.operaton.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl
import org.operaton.bpm.engine.impl.interceptor.Command
import org.springframework.stereotype.Service

/**
 * Turns a `resume-on-mail` filter into the filter of one particular waiting process.
 *
 * A filter value such as `pv:aanvragerEmail` or `doc:/zaaknummer` is a reference, and what it
 * refers to differs per process — which is the whole point: it is how a mail finds the one
 * case it belongs to. Valtimo resolves references like these when the engine executes a
 * plugin action, but the poller is not the engine: it reads the link itself, so it resolves
 * them itself, once for every waiting execution.
 *
 * Plain text in a filter is left as it is. A value only counts as a reference when its
 * prefix is one of the installed value resolvers, so a subject filter like `Re: aanvraag`
 * stays text.
 */
@SkipComponentScan
@Service
class MailFilterResolver(
    private val valueResolverService: ValueResolverService,
    private val processEngine: ProcessEngine,
) {
    /** Whether any value in [filter] is a reference that differs per process. */
    fun hasReferences(filter: ReceiveMailProperties): Boolean = filter.values().any { isReference(it) }

    /**
     * Returns [filter] with every reference replaced by its value for the execution
     * [executionId], or `null` when a reference has no value there. The fields that held a
     * reference are marked to match as whole values.
     *
     * `null` rather than a filter with that field blanked: a blank filter field matches every
     * mail, so a case whose `pv:aanvragerEmail` was never set would otherwise accept mail from
     * anyone.
     */
    fun resolve(
        filter: ReceiveMailProperties,
        executionId: String,
    ): ResolvedMailFilter? {
        val references = filter.values().filter { isReference(it) }.distinct()
        if (references.isEmpty()) return ResolvedMailFilter(filter)

        val resolved = resolveAgainst(executionId, references)

        fun valueOf(field: String?): String? {
            if (field == null || field !in references) return field
            // Trimmed: a case value typed with a stray space would otherwise never match.
            return resolved[field]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }

        val unresolved = references.filter { valueOf(it) == null }
        if (unresolved.isNotEmpty()) {
            logger.debug { "Filter value(s) $unresolved have no value for execution '$executionId'" }
            return null
        }

        return ResolvedMailFilter(
            properties =
                ReceiveMailProperties(
                    senderContains = valueOf(filter.senderContains),
                    subjectContains = valueOf(filter.subjectContains),
                    recipientContains = valueOf(filter.recipientContains),
                ),
            wholeValues =
                buildSet {
                    if (filter.senderContains in references) add(Field.SENDER)
                    if (filter.subjectContains in references) add(Field.SUBJECT)
                    if (filter.recipientContains in references) add(Field.RECIPIENT)
                },
        )
    }

    /**
     * Resolves inside an engine command, with the waiting execution as the variable scope.
     *
     * That is the same scope Valtimo hands its resolvers when it executes a plugin action,
     * so `pv:` sees exactly the variables it would see there, local ones included. The
     * execution entity is only usable as a scope while a command context is open, hence
     * the command.
     *
     * In a transaction of its own, not the mail's. A resolver that throws - `doc:` on a
     * process without a case document does - marks the transaction it ran in for rollback,
     * whether or not the exception is caught. Joined to the mail's transaction that would undo
     * the claim and every other case's resume, and the mail would come back on every poll.
     * The cost is that this transaction cannot see what the mail's own has not committed yet:
     * an execution created earlier in the same handling is not found, and does not match.
     */
    private fun resolveAgainst(
        executionId: String,
        references: List<String>,
    ): Map<String, Any?> {
        val configuration = processEngine.processEngineConfiguration as ProcessEngineConfigurationImpl
        return configuration.commandExecutorTxRequiresNew.execute(
            Command { commandContext ->
                val execution =
                    commandContext.executionManager.findExecutionById(executionId)
                        ?: return@Command emptyMap()
                valueResolverService.resolveValues(execution.processInstanceId, execution, references)
            },
        )
    }

    private fun isReference(value: String): Boolean {
        val prefix = value.substringBefore(DELIMITER, missingDelimiterValue = "")
        return prefix.isNotEmpty() && prefix in valueResolverService.getValueResolvers()
    }

    private fun ReceiveMailProperties.values(): List<String> =
        listOfNotNull(senderContains, subjectContains, recipientContains).filter { it.isNotBlank() }

    private companion object {
        private val logger = KotlinLogging.logger {}

        private const val DELIMITER = ":"
    }
}
