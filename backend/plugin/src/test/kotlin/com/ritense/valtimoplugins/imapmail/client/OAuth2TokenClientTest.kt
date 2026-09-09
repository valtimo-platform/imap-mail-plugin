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
import com.ritense.valtimoplugins.imapmail.BaseTest
import com.ritense.valtimoplugins.imapmail.domain.OAuth2Properties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.time.Instant

class OAuth2TokenClientTest : BaseTest() {
    private val objectMapper = ObjectMapper()
    private var now: Instant = Instant.parse("2026-09-09T12:00:00Z")

    @Test
    fun `should fetch and return an access token`() {
        val httpClient = httpClient(200, """{"access_token":"token-1","expires_in":3600}""")

        val token = client(httpClient).accessToken(properties())

        assertThat(token).isEqualTo("token-1")
    }

    @Test
    fun `should serve a second call from the cache`() {
        val httpClient = httpClient(200, """{"access_token":"token-1","expires_in":3600}""")
        val client = client(httpClient)

        client.accessToken(properties())
        client.accessToken(properties())

        verify(httpClient, times(1)).send(any(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    fun `should refetch once the cached token is close to expiring`() {
        val httpClient = httpClient(200, """{"access_token":"token-1","expires_in":120}""")
        val client = client(httpClient)

        client.accessToken(properties())
        // A minute of safety margin is subtracted, so a 120s token stops being served at 60s.
        now = now.plusSeconds(61)
        client.accessToken(properties())

        verify(httpClient, times(2)).send(any(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    fun `should not reuse a token across different credentials`() {
        val httpClient = httpClient(200, """{"access_token":"token-1","expires_in":3600}""")
        val client = client(httpClient)

        client.accessToken(properties(clientId = "client-a"))
        client.accessToken(properties(clientId = "client-b"))

        verify(httpClient, times(2)).send(any(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    fun `should not reuse a token issued under a rotated secret`() {
        val httpClient = httpClient(200, """{"access_token":"token-1","expires_in":3600}""")
        val client = client(httpClient)

        client.accessToken(properties(clientSecret = "old-secret"))
        client.accessToken(properties(clientSecret = "rotated-secret"))

        verify(httpClient, times(2)).send(any(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    fun `should refetch after the cache is invalidated`() {
        val httpClient = httpClient(200, """{"access_token":"token-1","expires_in":3600}""")
        val client = client(httpClient)

        client.accessToken(properties())
        client.invalidate(properties())
        client.accessToken(properties())

        verify(httpClient, times(2)).send(any(), any<HttpResponse.BodyHandler<String>>())
    }

    @Test
    fun `should include the error body when the token endpoint rejects the request`() {
        val httpClient = httpClient(401, """{"error":"invalid_client"}""")

        assertThatThrownBy { client(httpClient).accessToken(properties()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HTTP 401")
            .hasMessageContaining("invalid_client")
    }

    @Test
    fun `should fail clearly when the response has no access token`() {
        val httpClient = httpClient(200, """{"token_type":"Bearer"}""")

        assertThatThrownBy { client(httpClient).accessToken(properties()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("no 'access_token'")
    }

    @Test
    fun `should fail clearly when the response is not json`() {
        val httpClient = httpClient(200, "<html>gateway error</html>")

        assertThatThrownBy { client(httpClient).accessToken(properties()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not JSON")
    }

    private fun client(httpClient: HttpClient) = OAuth2TokenClient(objectMapper, httpClient) { now }

    private fun httpClient(
        status: Int,
        body: String,
    ): HttpClient {
        val response = mock<HttpResponse<String>>()
        whenever(response.statusCode()).thenReturn(status)
        whenever(response.body()).thenReturn(body)

        return mock<HttpClient>().also {
            whenever(it.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(response)
        }
    }

    private fun properties(
        clientId: String = "client-id",
        clientSecret: String = "client-secret",
    ) = OAuth2Properties(
        tokenUrl = "https://login.microsoftonline.com/tenant/oauth2/v2.0/token",
        clientId = clientId,
        clientSecret = clientSecret,
        scope = "https://outlook.office365.com/.default",
    )
}
