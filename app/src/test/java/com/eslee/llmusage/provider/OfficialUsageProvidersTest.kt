package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class OfficialUsageProvidersTest {
    private lateinit var server: MockWebServer
    private val account = Account("a", "openai-api", "test", AuthMode.API_KEY)
    @Before fun setup() { server = MockWebServer(); server.start() }
    @After fun cleanup() { server.shutdown() }
    private fun openAi(client: OkHttpClient = OkHttpClient()) = OpenAiUsageProvider(client, server.url("/").toString())
    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun page(input: Int, more: Boolean = false, cursor: String? = null) = """{"data":[{"results":[{"input_tokens":$input,"output_tokens":2,"num_model_requests":1}]}],"has_more":$more,"next_page":${cursor?.let { "\"$it\"" } ?: "null"}}"""

    @Test fun openAiPaginationAggregatesWithoutInventingQuota() = runBlocking {
        server.enqueue(response(page(3, true, "second")))
        server.enqueue(response(page(5)))
        val snapshot = (openAi().fetch(account, "fixture-key") as ProviderResult.Success).snapshot
        assertEquals(8.0, snapshot.buckets.first { it.id == "input_tokens" }.used!!, 0.0)
        assertNull(snapshot.buckets.first().limit)
        assertNull(snapshot.buckets.first().usedPercent)
        val first = server.takeRequest()
        assertEquals("/v1/organization/usage/completions", first.requestUrl!!.encodedPath)
        assertEquals("Bearer fixture-key", first.getHeader("Authorization"))
        assertEquals("second", server.takeRequest().requestUrl!!.queryParameter("page"))
    }
    @Test fun httpErrorsAreTypedAndDoNotEchoBodyOrSecret() = runBlocking {
        for ((status, expected) in mapOf(401 to ProviderErrorCode.AUTH_REQUIRED, 403 to ProviderErrorCode.PERMISSION_DENIED,
            404 to ProviderErrorCode.INVALID_RESPONSE, 429 to ProviderErrorCode.RATE_LIMITED, 500 to ProviderErrorCode.NETWORK)) {
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "120").setBody("sensitive-server-content"))
            val result = openAi().fetch(account, "fixture-key") as ProviderResult.Failure
            assertEquals(expected, result.code)
            assertFalse(result.message.contains("sensitive"))
            assertFalse(result.message.contains("fixture-key"))
            if (status == 429) assertEquals(120_000L, result.retryAfterMillis)
        }
    }
    @Test fun malformedEmptyAndRepeatedCursorFail() = runBlocking {
        for (body in listOf("not-json", "{}", """{"data":[],"has_more":false}""")) {
            server.enqueue(response(body))
            assertTrue(openAi().fetch(account, "fixture-key") is ProviderResult.Failure)
        }
        server.enqueue(response(page(1, true, "repeat")))
        server.enqueue(response(page(1, true, "repeat")))
        assertEquals(ProviderErrorCode.INVALID_RESPONSE, (openAi().fetch(account, "fixture-key") as ProviderResult.Failure).code)
    }
    @Test fun timeoutIsIsolated() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = OkHttpClient.Builder().readTimeout(100, TimeUnit.MILLISECONDS).build()
        val result = openAi(client).fetch(account, "fixture-key") as ProviderResult.Failure
        assertEquals(ProviderErrorCode.NETWORK_TIMEOUT, result.code)
    }
    @Test fun anthropicHandlesCacheTokensAndPagination() = runBlocking {
        server.enqueue(response("""{"data":[{"results":[{"uncached_input_tokens":5,"output_tokens":3,"cache_read_input_tokens":2,"cache_creation":{"ephemeral_1h_input_tokens":4,"ephemeral_5m_input_tokens":6}}]}],"has_more":true,"next_page":"next"}"""))
        server.enqueue(response("""{"data":[],"has_more":false}"""))
        val result = AnthropicUsageProvider(baseUrl = server.url("/").toString()).fetch(account.copy(providerId = "anthropic-api"), "fixture-key") as ProviderResult.Success
        assertEquals(5, result.snapshot.buckets.size)
        val request = server.takeRequest()
        assertEquals("fixture-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        assertEquals("next", server.takeRequest().requestUrl!!.queryParameter("page"))
    }
    @Test fun xaiPostsReadOnlyAnalyticsAndRejectsTruncation() = runBlocking {
        val provider = XaiUsageProvider(baseUrl = server.url("/").toString())
        val xai = account.copy(providerId = "xai-api", teamId = "team-test")
        server.enqueue(response("""{"timeSeries":[{"groupLabels":["API model"],"dataPoints":[{"values":[0.4]},{"values":[0.6]}]}],"limitReached":false}"""))
        val result = provider.fetch(xai, "fixture-key") as ProviderResult.Success
        assertEquals(1.0, result.snapshot.buckets.single().used!!, 0.00001)
        assertEquals(UsageUnit.USD, result.snapshot.buckets.single().unit)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/billing/teams/team-test/usage", request.requestUrl!!.encodedPath)
        assertTrue(request.body.readUtf8().contains("analyticsRequest"))
        server.enqueue(response("""{"timeSeries":[],"limitReached":true}"""))
        assertTrue(provider.fetch(xai, "fixture-key") is ProviderResult.Failure)
        assertEquals(ProviderErrorCode.CONFIGURATION, (provider.fetch(xai.copy(teamId = null), "fixture-key") as ProviderResult.Failure).code)
    }
    @Test fun retryAfterHttpDateAndInvalidValues() {
        val now = Instant.parse("2026-09-09T00:00:00Z").toEpochMilli()
        assertEquals(60_000L, retryAfterMillis("Wed, 9 Sep 2026 00:01:00 GMT", now))
        assertNull(retryAfterMillis("invalid", now))
        assertEquals(0L, retryAfterMillis("-1", now))
    }
}
