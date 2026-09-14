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
 * Thrown when a mail is over a hard limit and can therefore never be accepted, however often
 * it is retried.
 *
 * The distinction from an ordinary failure is what the poller acts on. A dropped connection
 * or a database hiccup leaves the message on the server, because the next poll stands a fair
 * chance of succeeding where this one did not. A rejection has no such chance: the mail will
 * be over the same limit in five minutes. Leaving it behind would mean re-downloading it on
 * every poll for as long as it sits in the folder, while occupying one of the
 * `maxMessagesPerPoll` slots — and since the candidate set is ordered oldest first, enough
 * rejected mail at the head of a folder stops new mail from being reached at all.
 *
 * So a rejected mail is post-processed like a handled one — marked read, moved or deleted —
 * and logged at error level. No case is created for it: the limits exist precisely because
 * such a mail is not safe to take in.
 *
 * Extends `IllegalStateException` so that a caller which only cares that the mail was
 * refused, not why, still catches it.
 */
class MailRejectedException(
    message: String,
) : IllegalStateException(message)
