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

package com.ritense.valtimoplugins.imapmail.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.valtimoplugins.imapmail.domain.OAuth2Properties
import io.github.oshai.kotlinlogging.KotlinLogging
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches OAuth2 client-credentials access tokens for SASL XOAUTH2, and caches them.
 *
 * Caching is not an optimisation here so much as politeness: polling every five minutes
 * against a token endpoint that issues hour-long tokens would be twelve pointless
 * round-trips per hour per mailbox, and Microsoft rate-limits token requests.
 *
 * The cache is a field on a single shared bean, so it must be safe for concurrent use — the
 * poller can process several mailboxes at once, and they may share one app registration.
 */
open class OAuth2TokenClient(
    private val objectMapper: ObjectMapper,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
            .build(),
    private val clock: () -> Instant = Instant::now,
) {
    private val cache = ConcurrentHashMap<String, CachedToken>()

    /**
     * Returns a valid access token, from cache when possible.
     *
     * Not synchronised across callers: two threads racing for the same cold key will both
     * fetch, and the second simply overwrites the first with an equally valid token. A lock
     * would serialise every mailbox behind the slowest token endpoint, which is worse than
     * an occasional duplicate request.
     */
    open fun accessToken(properties: OAuth2Properties): String {
        val key = properties.cacheKey()
        cache[key]?.takeIf { it.isUsableAt(clock()) }?.let { return it.token }

        val token = requestToken(properties)
        cache[key] = token
        return token.token
    }

    /**
     * Drops the cached token for these credentials, so the next call fetches a fresh one.
     *
     * Called when the server rejects an authentication that used a cached token: the token
     * may have been revoked before its stated expiry.
     */
    open fun invalidate(properties: OAuth2Properties) {
        cache.remove(properties.cacheKey())
    }

    private fun requestToken(properties: OAuth2Properties): CachedToken {
        val body =
            listOf(
                "grant_type" to "client_credentials",
                "client_id" to properties.clientId,
                "client_secret" to properties.clientSecret,
                "scope" to properties.scope,
            ).joinToString("&") { (name, value) ->
                "$name=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
            }

        val request =
            HttpRequest
                .newBuilder(URI.create(properties.tokenUrl))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException(
                    "Interrupted while fetching an access token from '${properties.tokenUrl}'",
                    e,
                )
            } catch (e: Exception) {
                throw IllegalStateException("Could not reach token endpoint '${properties.tokenUrl}'", e)
            }

        if (response.statusCode() !in SUCCESS_RANGE) {
            // The body of a failed token response carries the actual cause (wrong scope,
            // expired secret, consent missing) and no credentials, so it is safe and very
            // much worth logging - without it every misconfiguration looks identical.
            val body = response.body()?.take(ERROR_BODY_LIMIT)
            throw IllegalStateException(
                "Token endpoint '${properties.tokenUrl}' returned HTTP ${response.statusCode()}: $body",
            )
        }

        val parsed =
            try {
                objectMapper.readTree(response.body())
            } catch (e: Exception) {
                throw IllegalStateException(
                    "Token endpoint '${properties.tokenUrl}' returned a body that is not JSON",
                    e,
                )
            }

        // Blank counts as absent. An empty `access_token` would otherwise be cached and
        // presented to the mail server, turning a broken token endpoint into a stream of
        // authentication failures that name the mailbox instead of the endpoint.
        val accessToken =
            parsed.path("access_token").asText(null)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Token response from '${properties.tokenUrl}' has no 'access_token'")
        val expiresInSeconds = parsed.path("expires_in").asLong(DEFAULT_EXPIRY_SECONDS)

        logger.debug { "Obtained an access token for client '${properties.clientId}', valid for ${expiresInSeconds}s" }
        return CachedToken(
            token = accessToken,
            // Expire early so a token cannot go stale mid-connection.
            usableUntil = clock().plusSeconds((expiresInSeconds - EXPIRY_MARGIN_SECONDS).coerceAtLeast(0)),
        )
    }

    private data class CachedToken(
        val token: String,
        val usableUntil: Instant,
    ) {
        fun isUsableAt(now: Instant): Boolean = now.isBefore(usableUntil)
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        private val SUCCESS_RANGE = 200..299
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val DEFAULT_EXPIRY_SECONDS = 3600L
        private const val EXPIRY_MARGIN_SECONDS = 60L
        private const val ERROR_BODY_LIMIT = 512
    }
}
