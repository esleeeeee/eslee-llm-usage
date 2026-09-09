package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val usageClient = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS)
    .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
    .followRedirects(false).followSslRedirects(false).build()
private val json = Json { ignoreUnknownKeys = true }
private class FetchException(val failure: ProviderResult.Failure) : Exception()
private fun invalid(): Nothing = throw FetchException(ProviderResult.Failure(ProviderErrorCode.INVALID_RESPONSE, "사용량 응답 형식을 확인할 수 없습니다."))
private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: invalid()
private fun JsonElement.obj(): JsonObject = this as? JsonObject ?: invalid()
private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 }
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.flag(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false

internal fun retryAfterMillis(value: String?, now: Long): Long? {
    if (value == null) return null
    value.toLongOrNull()?.let { return it.coerceIn(0, 604_800) * 1000 }
    return runCatching { (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now).coerceIn(0, 604_800_000) }.getOrNull()
}

private suspend fun OkHttpClient.read(request: Request): JsonObject = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!it.isSuccessful) {
                        val code = when (it.code) {
                            401 -> ProviderErrorCode.AUTH_REQUIRED
                            403 -> ProviderErrorCode.PERMISSION_DENIED
                            429 -> ProviderErrorCode.RATE_LIMITED
                            in 500..599 -> ProviderErrorCode.NETWORK
                            else -> ProviderErrorCode.INVALID_RESPONSE
                        }
                        throw FetchException(ProviderResult.Failure(code, "사용량 서버 응답: HTTP ${it.code}", retryAfterMillis(it.header("Retry-After"), System.currentTimeMillis())))
                    }
                    val body = it.body ?: invalid()
                    if (body.contentLength() > 4_194_304) invalid()
                    val source = body.source()
                    source.request(4_194_305)
                    if (source.buffer.size > 4_194_304) invalid()
                    val result = json.parseToJsonElement(source.readUtf8()).obj()
                    if (continuation.isActive) continuation.resume(result)
                }
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
        }
    })
}

abstract class OfficialUsageProvider : UsageProvider {
    protected abstract suspend fun load(account: Account, secret: String, now: Long): List<UsageBucket>
    final override suspend fun fetch(account: Account, secret: String): ProviderResult {
        if (secret.isBlank() || secret.any { it == '\r' || it == '\n' }) return ProviderResult.Failure(ProviderErrorCode.AUTH_REQUIRED, "유효한 관리 API 키가 필요합니다.")
        return try {
            val now = System.currentTimeMillis()
            val buckets = load(account, secret, now)
            if (buckets.isEmpty()) ProviderResult.Failure(ProviderErrorCode.INVALID_RESPONSE, "조회 구간에 제공된 사용량 값이 없습니다. 이전 성공 결과를 유지합니다.")
            else ProviderResult.Success(UsageSnapshot(account.id, account.providerId, buckets.map(UsageNormalizer::normalize), now,
                note = "오늘 UTC 기준 API 사용 기록입니다. 구독 한도·잔여량 또는 결제 청구서가 아닙니다."))
        } catch (e: FetchException) { e.failure
        } catch (e: SocketTimeoutException) { ProviderResult.Failure(ProviderErrorCode.NETWORK_TIMEOUT, "사용량 서버 응답 시간이 초과되었습니다.")
        } catch (e: IOException) { ProviderResult.Failure(ProviderErrorCode.NETWORK, "사용량 서버에 연결할 수 없습니다.")
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ProviderResult.Failure(ProviderErrorCode.INVALID_RESPONSE, "사용량 응답 형식을 확인할 수 없습니다.") }
    }
    protected fun start(now: Long): Instant = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()
}

class OpenAiUsageProvider(private val client: OkHttpClient = usageClient, private val baseUrl: String = "https://api.openai.com/") : OfficialUsageProvider() {
    override suspend fun load(account: Account, secret: String, now: Long): List<UsageBucket> {
        val totals = linkedMapOf<String, Double>()
        var page: String? = null
        val seen = mutableSetOf<String>()
        do {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/organization/usage/completions")
                .addQueryParameter("start_time", start(now).epochSecond.toString()).addQueryParameter("end_time", (now / 1000).toString())
                .addQueryParameter("bucket_width", "1d").addQueryParameter("limit", "31")
            page?.let { url.addQueryParameter("page", it) }
            val root = client.read(Request.Builder().url(url.build()).header("Authorization", "Bearer $secret").build())
            root.array("data").forEach { bucket -> bucket.obj().array("results").forEach { row ->
                for (key in listOf("input_tokens", "output_tokens", "num_model_requests")) {
                    val value = row.obj().number(key) ?: invalid()
                    totals[key] = ((totals[key] ?: 0.0) + value).takeIf(Double::isFinite) ?: invalid()
                }
            } }
            page = if (root.flag("has_more")) root.text("next_page")?.takeIf { it.isNotBlank() } ?: invalid() else null
            if (page != null && (!seen.add(page) || seen.size > 100)) invalid()
        } while (page != null)
        return totals.map { (key, value) -> UsageBucket(key, when (key) { "input_tokens" -> "오늘 입력 토큰"; "output_tokens" -> "오늘 출력 토큰"; else -> "오늘 요청" },
            used = value, unit = if (key == "num_model_requests") UsageUnit.REQUESTS else UsageUnit.TOKENS, windowLabel = "오늘 UTC") }
    }
}

class AnthropicUsageProvider(private val client: OkHttpClient = usageClient, private val baseUrl: String = "https://api.anthropic.com/") : OfficialUsageProvider() {
    override suspend fun load(account: Account, secret: String, now: Long): List<UsageBucket> {
        val totals = linkedMapOf<String, Double>()
        var page: String? = null
        val seen = mutableSetOf<String>()
        do {
            val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/organizations/usage_report/messages")
                .addQueryParameter("starting_at", start(now).toString()).addQueryParameter("ending_at", Instant.ofEpochMilli(now).toString())
                .addQueryParameter("bucket_width", "1d").addQueryParameter("limit", "31")
            page?.let { url.addQueryParameter("page", it) }
            val root = client.read(Request.Builder().url(url.build()).header("x-api-key", secret).header("anthropic-version", "2023-06-01").build())
            root.array("data").forEach { bucket -> bucket.obj().array("results").forEach { row ->
                val values = row.obj()
                for (key in listOf("uncached_input_tokens", "cache_read_input_tokens", "output_tokens")) {
                    val value = values.number(key) ?: invalid()
                    totals[key] = ((totals[key] ?: 0.0) + value).takeIf(Double::isFinite) ?: invalid()
                }
                (values["cache_creation"] as? JsonObject)?.let { cache ->
                    for (key in listOf("ephemeral_1h_input_tokens", "ephemeral_5m_input_tokens")) {
                        val value = cache.number(key) ?: invalid()
                        totals[key] = ((totals[key] ?: 0.0) + value).takeIf(Double::isFinite) ?: invalid()
                    }
                }
            } }
            page = if (root.flag("has_more")) root.text("next_page")?.takeIf { it.isNotBlank() } ?: invalid() else null
            if (page != null && (!seen.add(page) || seen.size > 100)) invalid()
        } while (page != null)
        val labels = mapOf("uncached_input_tokens" to "오늘 입력 토큰 (캐시 제외)", "cache_read_input_tokens" to "오늘 캐시 읽기 토큰", "output_tokens" to "오늘 출력 토큰", "ephemeral_1h_input_tokens" to "오늘 1시간 캐시 생성", "ephemeral_5m_input_tokens" to "오늘 5분 캐시 생성")
        return totals.map { (key, value) -> UsageBucket(key, labels.getValue(key), used = value, unit = UsageUnit.TOKENS, windowLabel = "오늘 UTC") }
    }
}

class XaiUsageProvider(private val client: OkHttpClient = usageClient, private val baseUrl: String = "https://management-api.x.ai/") : OfficialUsageProvider() {
    override suspend fun load(account: Account, secret: String, now: Long): List<UsageBucket> {
        val team = account.teamId?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
            ?: throw FetchException(ProviderResult.Failure(ProviderErrorCode.CONFIGURATION, "xAI Team ID를 입력하세요."))
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        val body = buildJsonObject { putJsonObject("analyticsRequest") {
            putJsonObject("timeRange") { put("startTime", format.format(start(now))); put("endTime", format.format(Instant.ofEpochMilli(now))); put("timezone", "Etc/GMT") }
            put("timeUnit", "TIME_UNIT_DAY")
            putJsonArray("values") { add(buildJsonObject { put("name", "usd"); put("aggregation", "AGGREGATION_SUM") }) }
            putJsonArray("groupBy") { add("description") }; putJsonArray("filters") {}
        } }
        val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/billing/teams").addPathSegment(team).addPathSegment("usage").build()
        val root = client.read(Request.Builder().url(url).header("Authorization", "Bearer $secret").post(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (root.flag("limitReached")) throw FetchException(ProviderResult.Failure(ProviderErrorCode.INVALID_RESPONSE, "xAI 조회 결과가 잘려 전체 사용량을 확인할 수 없습니다."))
        return root.array("timeSeries").mapIndexedNotNull { index, element ->
            val series = element.obj()
            val values = series.array("dataPoints").map {
                val point = it.obj().array("values")
                if (point.size != 1) invalid()
                (point.single() as? JsonPrimitive)?.doubleOrNull?.takeIf { n -> n.isFinite() && n >= 0 } ?: invalid()
            }
            if (values.isEmpty()) null else UsageBucket("cost-$index", (series["groupLabels"] as? JsonArray)?.joinToString(" · ") { (it as? JsonPrimitive)?.contentOrNull ?: "API" } ?: "API 비용",
                used = values.sum().takeIf(Double::isFinite) ?: invalid(), unit = UsageUnit.USD, windowLabel = "오늘 UTC", scope = QuotaScope.MODEL)
        }
    }
}
