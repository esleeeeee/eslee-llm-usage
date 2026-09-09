package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.core.web.WebNavigationPolicy

enum class ConnectorStatus { STABLE_OFFICIAL, OFFICIAL_UNVERIFIED, EXPERIMENTAL_WEB, PLACEHOLDER_UNSUPPORTED, DISABLED_POLICY }
data class ProviderCapabilities(
    val supportsMultipleAccounts: Boolean = true,
    val supportsBackgroundSync: Boolean = false,
    val supportsForegroundSync: Boolean = false,
    val supportsOfficialApi: Boolean = false,
    val supportsConsumerWeb: Boolean = false,
    val supportsPlanInfo: Boolean = false,
    val supportsResetTime: Boolean = false,
    val supportsPercentUsage: Boolean = false,
    val supportsExtraCredits: Boolean = false,
    val requiresAdminKey: Boolean = false,
)

data class ProviderDefinition(
    val id: String,
    val displayName: String,
    val authMode: AuthMode,
    val supported: Boolean,
    val description: String,
    val validation: String,
    val status: ConnectorStatus,
    val capabilities: ProviderCapabilities,
    val loginUrl: String? = null,
    val usageUrl: String? = null,
    val allowedHosts: Set<String> = emptySet(),
)

enum class ProviderErrorCode {
    AUTH_REQUIRED, PERMISSION_DENIED, RATE_LIMITED, NETWORK, NETWORK_TIMEOUT,
    INVALID_RESPONSE, PARSE_FAILED, PARSER_OUTDATED, UNSUPPORTED, CONFIGURATION,
}
sealed interface ProviderResult {
    data class Success(val snapshot: UsageSnapshot) : ProviderResult
    data class Failure(val code: ProviderErrorCode, val message: String, val retryAfterMillis: Long? = null) : ProviderResult
}
fun interface UsageProvider { suspend fun fetch(account: Account, secret: String): ProviderResult }

class ProviderRegistry(debug: Boolean) {
    private val official = ProviderCapabilities(supportsBackgroundSync = true, supportsOfficialApi = true, requiresAdminKey = true)
    private val web = ProviderCapabilities(supportsForegroundSync = true, supportsConsumerWeb = true,
        supportsPlanInfo = true, supportsResetTime = true, supportsPercentUsage = true)
    val definitions: List<ProviderDefinition> = buildList {
        add(consumer("chatgpt", "Codex", "chatgpt.com", "https://chatgpt.com/auth/login", "https://chatgpt.com/codex/settings/usage",
            setOf("auth.openai.com", "auth0.openai.com", "chat.openai.com", "www.chatgpt.com")))
        add(api("openai-api", "OpenAI API", "조직 Admin key로 오늘(UTC)의 토큰·요청 사용량 조회. ChatGPT 구독과 별도."))
        add(consumer("claude", "Claude", "claude.ai", "https://claude.ai/login", "https://claude.ai/settings/usage"))
        add(api("anthropic-api", "Anthropic API", "조직 Admin key로 오늘(UTC)의 토큰 사용량 조회. Claude 구독과 별도."))
        add(consumer("grok", "Grok", "grok.com", "https://grok.com/sign-in", "https://grok.com/?_s=usage", setOf("accounts.x.ai", "www.grok.com")))
        add(api("xai-api", "xAI API", "Management key와 Team ID로 오늘(UTC)의 API 비용 조회. SuperGrok 구독과 별도."))
        for ((id, title) in listOf("gemini" to "Gemini", "perplexity" to "Perplexity")) add(
            ProviderDefinition(id, title, AuthMode.NONE, false, "공개 consumer Usage API 및 검증된 화면 파서가 없어 현재 지원하지 않습니다.",
                "미지원", ConnectorStatus.PLACEHOLDER_UNSUPPORTED, ProviderCapabilities()))
        if (debug) add(ProviderDefinition("demo", "Demo · 테스트 데이터", AuthMode.NONE, true,
            "실제 사용량이 아닌 디버그 전용 샘플입니다.", "샘플 데이터", ConnectorStatus.EXPERIMENTAL_WEB,
            ProviderCapabilities(supportsBackgroundSync = true, supportsPercentUsage = true, supportsResetTime = true)))
    }
    private fun api(id: String, title: String, description: String) = ProviderDefinition(id, title, AuthMode.API_KEY, true,
        description, "공식 문서 기반 구현 · 실제 계정 검증 필요", ConnectorStatus.OFFICIAL_UNVERIFIED, official,
        usageUrl = when (id) {
            "openai-api" -> "https://platform.openai.com/usage"
            "anthropic-api" -> "https://platform.claude.com/usage"
            else -> "https://console.x.ai/"
        })
    private fun consumer(
        id: String,
        title: String,
        host: String,
        login: String,
        usage: String,
        loginHosts: Set<String> = emptySet(),
    ) = ProviderDefinition(
        id, title, AuthMode.WEB_PROFILE, true,
        when (id) {
            "chatgpt" -> "ChatGPT 계정의 Codex 사용량(5시간·주간 한도, reset, credit)을 공식 Usage 화면에서 읽습니다. 실험적 parser입니다."
            "grok" -> "SuperGrok Settings Usage의 주간 사용량, 제품별 비율, reset, Extra Usage Credits를 읽습니다. 실험적 parser입니다."
            else -> "격리된 로그인 화면에서 사용자가 표시한 Usage 텍스트만 읽습니다. 실험적 기능이며 UI 변경 시 실패할 수 있습니다."
        },
        "최소 fixture 검증 · 실제 로그인/UI 미검증", ConnectorStatus.EXPERIMENTAL_WEB,
        web.copy(supportsExtraCredits = id == "grok" || id == "chatgpt"), login, usage,
        WebNavigationPolicy.withOAuth(setOf(host) + loginHosts),
    )

    fun definition(id: String): ProviderDefinition? = definitions.find { it.id == id }
    fun adapter(id: String): UsageProvider? = when {
        definition(id) == null -> null
        id == "openai-api" -> OpenAiUsageProvider()
        id == "anthropic-api" -> AnthropicUsageProvider()
        id == "xai-api" -> XaiUsageProvider()
        id == "demo" -> UsageProvider { account, _ ->
            val now = System.currentTimeMillis()
            ProviderResult.Success(UsageSnapshot(account.id, "demo", listOf(
                UsageBucket("weekly", "주간 사용량 · 샘플", unit = UsageUnit.PERCENT, usedPercent = 38.0, resetAt = now + 86_400_000),
                UsageBucket("session", "세션 · 샘플", unit = UsageUnit.PERCENT, usedPercent = 72.0, resetAt = now + 14_400_000),
            ).map(UsageNormalizer::normalize), now, SnapshotSource.DEMO, "DEBUG 전용 가상 데이터", planName = "Demo"))
        }
        else -> UsageProvider { _, _ -> ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED,
            if (definition(id)?.authMode == AuthMode.WEB_PROFILE) "앱의 로그인/사용량 화면에서 새로고침하세요." else "현재 지원하지 않는 연결입니다.") }
    }
}
