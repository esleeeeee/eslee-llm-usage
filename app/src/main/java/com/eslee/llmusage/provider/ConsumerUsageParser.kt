package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import java.time.Instant
import java.time.OffsetDateTime

/** Conservative parsing of text the user can see; no private API or credential extraction. */
object ConsumerUsageParser {
    const val VERSION = "1.0.0"
    private data class Label(val id: String, val title: String, val pattern: Regex)
    private fun label(id: String, title: String, pattern: String) = Label(id, title, Regex(pattern, RegexOption.IGNORE_CASE))
    private val labels = mapOf(
        "grok" to listOf(label("weekly", "Weekly usage", "weekly usage|주간 사용량"),
            label("chat", "Chat", "^chat$"), label("imagine", "Imagine", "^imagine$"),
            label("voice", "Voice", "^voice$"), label("build", "Build", "^build$"), label("api", "API", "^api$")),
        "claude" to listOf(label("session", "Current session", "current session|5[- ]hour(?: session)?|현재 세션|5시간"),
            label("weekly", "Weekly usage", "weekly(?: usage| limits?)?|all models|주간(?: 사용량| 한도)?|모든 모델"),
            label("sonnet", "Sonnet", "sonnet only|sonnet만")),
        "chatgpt" to listOf(label("codex", "Codex usage", "codex(?: usage)?|코덱스 사용량"),
            label("weekly", "Weekly usage", "weekly usage|weekly limit|주간 사용량|주간 한도"),
            label("session", "Session usage", "session usage|5[- ]hour(?: usage| limit)?|세션 사용량|5시간"),
            label("work", "Work usage", "work usage|작업 사용량"),
            label("model", "Model usage", "^(?:GPT[- ][\\w. -]+|o[134](?:[- ][\\w. -]+)?)(?:usage|limit|사용량|한도)?$")),
    )
    private val percentage = Regex("(?<![\\d.])(\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val ratio = Regex("(?<![\\d.])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*(?:/|of|중)\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val resetLabel = Regex("reset|리셋|초기화|재설정", RegexOption.IGNORE_CASE)

    fun parse(providerId: String, accountId: String, visibleText: String, fetchedAt: Long = System.currentTimeMillis()): ProviderResult {
        val dictionary = labels[providerId] ?: return ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED, "이 서비스의 화면 파서는 지원하지 않습니다.")
        if (visibleText.length > 1_000_000) return ProviderResult.Failure(ProviderErrorCode.PARSE_FAILED, "화면 텍스트가 너무 큽니다.")
        val lines = visibleText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val matches = lines.mapIndexedNotNull { index, line -> dictionary.firstOrNull { it.pattern.containsMatchIn(line) }?.let { index to it } }
        val buckets = matches.mapIndexedNotNull { index, (start, label) ->
            val end = minOf(matches.getOrNull(index + 1)?.first ?: lines.size, start + 6)
            val section = lines.subList(start, end).takeWhile {
                !Regex("Extra Usage Credits|upgrade|special offer|save \\d|업그레이드|할인", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }.joinToString("\n")
            val percentLine = section.lineSequence().firstOrNull { percentage.containsMatchIn(it) }
            val percent = percentLine?.let { percentage.find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
            val remaining = percentLine?.let { Regex("remaining|left|잔여|남음", RegexOption.IGNORE_CASE).containsMatchIn(it) } == true
            val rawRatio = ratio.find(section)
            val used = rawRatio?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val limit = rawRatio?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()
            val resetAt = parseReset(section, fetchedAt)
            if (percent == null && used == null && resetAt == null) null
            else UsageNormalizer.normalize(UsageBucket(label.id, if (label.id == "model") lines[start] else label.title,
                used = used, limit = limit, unit = if (rawRatio != null) UsageUnit.REQUESTS else UsageUnit.PERCENT,
                usedPercent = percent.takeUnless { remaining }, remainingPercent = percent.takeIf { remaining }, resetAt = resetAt,
                confidence = Confidence.REPORTED, scope = if (label.id in setOf("weekly", "session")) QuotaScope.ACCOUNT else QuotaScope.FEATURE))
        }.distinctBy { it.id }
        if (buckets.isEmpty()) {
            val login = Regex("sign in to|log in to|continue with (google|apple)|이메일로 로그인|로그인하세요", RegexOption.IGNORE_CASE).containsMatchIn(visibleText)
            return ProviderResult.Failure(if (login) ProviderErrorCode.AUTH_REQUIRED else ProviderErrorCode.PARSE_FAILED,
                if (login) "서비스에 직접 로그인한 뒤 사용량 화면을 여세요." else "알려진 사용량 숫자나 명시적인 리셋 시각을 찾지 못했습니다. 이전 결과를 유지합니다.")
        }
        val creditLine = Regex("Extra Usage Credits[\\s:]*\\$([0-9]+(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE).find(visibleText)
        val credit = if (providerId == "grok") creditLine?.groupValues?.get(1)?.toDoubleOrNull()?.let(::CreditBalance) else null
        val plan = Regex("\\b(SuperGrok(?: Heavy)?|Claude (?:Pro|Max)|ChatGPT (?:Plus|Pro|Business))\\b", RegexOption.IGNORE_CASE).find(visibleText)?.value
        return ProviderResult.Success(UsageSnapshot(accountId, providerId, buckets, fetchedAt, SnapshotSource.VISIBLE_PAGE,
            "공식 화면의 표시 텍스트 · parser $VERSION · 표시되지 않은 값은 알 수 없음", planName = plan, extraCredits = credit,
            syncMode = SyncMode.FOREGROUND_ONLY, status = if (buckets.any { it.usedPercent == null && it.used == null }) SnapshotStatus.PARTIAL else SnapshotStatus.SUCCESS,
            primaryBucketId = if (providerId == "claude") "session" else "weekly", parserVersion = VERSION))
    }

    internal fun parseReset(section: String, now: Long): Long? {
        val line = section.lineSequence().firstOrNull { resetLabel.containsMatchIn(it) } ?: return null
        val iso = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})").find(line)?.value
        if (iso != null) return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        if (!Regex("\\bin\\b|후", RegexOption.IGNORE_CASE).containsMatchIn(line)) return null
        val hours = Regex("(\\d+)\\s*(?:hours?|hrs?|h\\b|시간)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:minutes?|mins?|m\\b|분)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val days = Regex("(\\d+)\\s*(?:days?|d\\b|일)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        if (days > 366 || hours > 8_784 || minutes > 527_040) return null
        val duration = days * 86_400_000 + hours * 3_600_000 + minutes * 60_000
        return if (duration in 1..(366L * 86_400_000)) Instant.ofEpochMilli(now).plusMillis(duration).toEpochMilli() else null
    }
}
