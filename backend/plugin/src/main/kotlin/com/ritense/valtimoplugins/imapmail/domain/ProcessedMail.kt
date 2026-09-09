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

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Marker row proving a mail already started a process.
 *
 * The primary key is what makes this work on more than one node: two pods polling the same
 * mailbox both fetch the message, both try to insert, and the loser gets a constraint
 * violation instead of starting a second case. The insert is therefore flushed before the
 * process is started, while staying in the same transaction as it, so that a process which
 * fails to start also releases its claim — see `IncomingMailHandler.handle`.
 */
@Entity
@Table(name = "imap_mail_processed")
class ProcessedMail(
    @Id
    @Column(name = "mail_identity")
    val mailIdentity: String,
    @Column(name = "plugin_configuration_id", nullable = false)
    val pluginConfigurationId: String,
    @Column(name = "processed_at", nullable = false)
    val processedAt: Instant = Instant.now(),
)
