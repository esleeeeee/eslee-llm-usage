package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Conservative parsing of text the user can see; no private API or credential extraction. */
object ConsumerUsageParser {
    const val VERSION = "1.2.0"
    private data class Label(val id: String, val title: String, val pattern: Regex)
    private fun label(id: String, title: String, pattern: String) = Label(id, title, Regex(pattern, RegexOption.IGNORE_CASE))
    private val labels = mapOf(
        "grok" to listOf(label("weekly", "Weekly usage", "weekly usage|주간 사용량"),
            label("chat", "Chat", "^chat$"), label("imagine", "Imagine", "^imagine$"),
            label("voice", "Voice", "^voice$"), label("build", "Build", "^build$"), label("api", "API", "^api$")),
        "claude" to listOf(label("session", "Current session", "current session|5[- ]hour(?: session)?|현재 세션|5시간"),
            label("weekly", "Weekly usage", "weekly(?: usage| limits?)?|all models|주간(?: 사용량| 한도)?|모든 모델"),
            label("sonnet", "Sonnet", "sonnet only|sonnet만")),
        "chatgpt" to listOf(
            label("session", "5-hour usage", "5[- ]hour(?:\\s+(?:usage|limit))?|five[- ]hour|5h(?:\\s+(?:usage|limit))?|세션 사용량|5시간(?: 사용)?(?: 한도)?"),
            label("weekly", "Weekly usage", "weekly usage(?: limit)?|weekly limit|주간 사용량(?: 한도)?|주간 한도|주간 사용 한도"),
            label("reserve", "Reserve usage", "gpt-reserve|reserve(?: usage| limit)?|spark"),
            label("session", "Codex usage", "codex(?: usage)?|코덱스 사용량"),
            label("work", "Work usage", "work usage|작업 사용량"),
            label("model", "Model usage", "^(?:GPT[- ][\\w. -]+|o[134](?:[- ][\\w. -]+)?)(?:usage|limit|사용량|한도)?$")),
    )
    private val percentage = Regex("(?<![\\d.])(\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val ratio = Regex("(?<![\\d.])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*(?:/|of|중)\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val usedMarker = Regex("\\bused\\b|\\butili[sz]ed\\b|\\bconsumed\\b|사용됨|사용한", RegexOption.IGNORE_CASE)
    private val remainingMarker = Regex("\\bremaining\\b|\\bleft\\b|잔여|남음|남아|남은", RegexOption.IGNORE_CASE)
    private val resetLabel = Regex("reset|리셋|초기화|재설정", RegexOption.IGNORE_CASE)
    private val koreanResetTime = Regex(
        "(?:(\\d{4})\\s*(?:\\.|년)\\s*(\\d{1,2})\\s*(?:\\.|월)\\s*(\\d{1,2})\\s*(?:\\.|일)?\\s*)?" +
            "(오전|오후)\\s*(\\d{1,2}):(\\d{2})",
        RegexOption.IGNORE_CASE,
    )

    fun parse(
        providerId: String,
        accountId: String,
        visibleText: String,
        fetchedAt: Long = System.currentTimeMillis(),
        localZone: ZoneId = ZoneId.systemDefault(),
    ): ProviderResult {
        val dictionary = labels[providerId] ?: return ProviderResult.Failure(ProviderErrorCode.UNSUPPORTED, "이 서비스의 화면 파서는 지원하지 않습니다.")
        if (visibleText.length > 1_000_000) return ProviderResult.Failure(ProviderErrorCode.PARSE_FAILED, "화면 텍스트가 너무 큽니다.")
        val lines = visibleText.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val matches = lines.mapIndexedNotNull { index, line -> dictionary.firstOrNull { it.pattern.containsMatchIn(line) }?.let { index to it } }
        val buckets = matches.mapIndexedNotNull { index, (start, label) ->
            val end = minOf(matches.getOrNull(index + 1)?.first ?: lines.size, start + 6)
            val sectionLines = lines.subList(start, end).takeWhile {
                !Regex("Extra Usage Credits|upgrade|special offer|save \\d|업그레이드|할인", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }
            val section = sectionLines.joinToString("\n")
            val percentValues = parsePercentValues(sectionLines)
            val rawRatio = ratio.find(section)
            val used = rawRatio?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val limit = rawRatio?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()
            val resetAt = parseReset(section, fetchedAt, localZone)
            if (percentValues.used == null && percentValues.remaining == null && used == null && resetAt == null) null
            else UsageNormalizer.normalize(UsageBucket(label.id, if (label.id == "model") lines[start] else label.title,
                used = used, limit = limit, unit = if (rawRatio != null) UsageUnit.REQUESTS else UsageUnit.PERCENT,
                usedPercent = percentValues.used, remainingPercent = percentValues.remaining, resetAt = resetAt,
                confidence = Confidence.REPORTED, scope = if (label.id in setOf("weekly", "session")) QuotaScope.ACCOUNT else QuotaScope.FEATURE))
        }.groupBy { it.id }.values.map { candidates ->
            candidates.maxByOrNull(::bucketEvidence) ?: candidates.first()
        }
        val withResets = buckets + listOfNotNull(parseBankedResets(visibleText))
        if (withResets.isEmpty()) {
            val login = Regex("sign in to|log in to|continue with (google|apple)|이메일로 로그인|로그인하세요", RegexOption.IGNORE_CASE).containsMatchIn(visibleText)
            return ProviderResult.Failure(if (login) ProviderErrorCode.AUTH_REQUIRED else ProviderErrorCode.PARSE_FAILED,
                if (login) "서비스에 직접 로그인한 뒤 사용량 화면을 여세요." else "알려진 사용량 숫자나 명시적인 리셋 시각을 찾지 못했습니다. 이전 결과를 유지합니다.")
        }
        val creditLine = Regex(
            "(?:^|\\n)\\s*(?:Extra Usage Credits|Credits|남은 크레딧)[ \\t:：]*(?:\\r?\\n[ \\t]*)?\\$?([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        ).find(lines.joinToString("\n"))
        val credit = if (providerId in setOf("grok", "chatgpt")) {
            creditLine?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()?.let(::CreditBalance)
        } else null
        val plan = Regex("\\b(SuperGrok(?:\\s+(?:Plus|Heavy|Pro))?|Claude (?:Pro|Max)|ChatGPT (?:Plus|Pro|Business|Go))\\b", RegexOption.IGNORE_CASE).find(visibleText)?.value
        val primary = when {
            providerId == "claude" -> "session"
            providerId == "chatgpt" && withResets.any { it.id == "session" } -> "session"
            else -> "weekly"
        }
        return ProviderResult.Success(UsageSnapshot(accountId, providerId, withResets, fetchedAt, SnapshotSource.VISIBLE_PAGE,
            "공식 화면의 표시 텍스트 · parser $VERSION · 표시되지 않은 값은 알 수 없음", planName = plan, extraCredits = credit,
            syncMode = SyncMode.FOREGROUND_ONLY, status = if (withResets.any { it.usedPercent == null && it.used == null && it.remaining == null }) SnapshotStatus.PARTIAL else SnapshotStatus.SUCCESS,
            primaryBucketId = primary, parserVersion = VERSION))
    }

    private enum class PercentMeaning { USED, REMAINING, UNKNOWN }

    private data class PercentReading(val value: Double, val meaning: PercentMeaning)

    private data class PercentValues(val used: Double?, val remaining: Double?)

    private fun bucketEvidence(bucket: UsageBucket): Int =
        listOf(bucket.usedPercent, bucket.remainingPercent).count { it != null } * 2 +
            listOf(bucket.used, bucket.limit, bucket.remaining).count { it != null } +
            if (bucket.resetAt != null) 1 else 0

    /**
     * A progress value can be exposed by WebView as a separate line from its
     * label and its "used"/"remaining" word. Only the adjacent lines are
     * considered semantic context; unrelated page percentages are ignored.
     */
    private fun parsePercentValues(lines: List<String>): PercentValues {
        val readings = lines.flatMapIndexed { lineIndex, line ->
            percentage.findAll(line).mapNotNull { match ->
                val value = match.groupValues[1].toDoubleOrNull()?.takeIf { it in 0.0..100.0 } ?: return@mapNotNull null
                PercentReading(value, classifyPercent(lines, lineIndex, match))
            }.toList()
        }
        val usedValues = readings.filter { it.meaning == PercentMeaning.USED }.map { it.value }.distinct()
        val remainingValues = readings.filter { it.meaning == PercentMeaning.REMAINING }.map { it.value }.distinct()
        val used = usedValues.singleOrNull()
        val remaining = remainingValues.singleOrNull()
        if (used != null && remaining != null) {
            return if (kotlin.math.abs(used + remaining - 100.0) <= 0.5) {
                PercentValues(used, remaining)
            } else {
                // Two contradictory explicit readings are safer as unknown.
                PercentValues(null, null)
            }
        }
        if (used != null || remaining != null) return PercentValues(used, remaining)

        val unlabeledValues = readings.filter { it.meaning == PercentMeaning.UNKNOWN }.map { it.value }.distinct()
        return PercentValues(unlabeledValues.singleOrNull(), null)
    }

    private fun classifyPercent(lines: List<String>, lineIndex: Int, token: MatchResult): PercentMeaning {
        data class Marker(val meaning: PercentMeaning, val distance: Int)
        val markers = buildList {
            val firstLine = maxOf(0, lineIndex - 1)
            val lastLine = minOf(lines.lastIndex, lineIndex + 1)
            for (candidateLine in firstLine..lastLine) {
                val lineDistance = kotlin.math.abs(candidateLine - lineIndex) * 10_000
                usedMarker.findAll(lines[candidateLine]).forEach {
                    add(Marker(PercentMeaning.USED, lineDistance + kotlin.math.abs(it.range.first - token.range.first)))
                }
                remainingMarker.findAll(lines[candidateLine]).forEach {
                    add(Marker(PercentMeaning.REMAINING, lineDistance + kotlin.math.abs(it.range.first - token.range.first)))
                }
            }
        }
        val nearest = markers.minOfOrNull { it.distance } ?: return PercentMeaning.UNKNOWN
        val meanings = markers.filter { it.distance == nearest }.map { it.meaning }.distinct()
        return meanings.singleOrNull() ?: PercentMeaning.UNKNOWN
    }

    private fun parseBankedResets(visibleText: String): UsageBucket? {
        val count = Regex("(\\d+)\\s+resets?\\s+available|reset available[^\\d]{0,8}(\\d+)|banked resets?[^\\d]{0,8}(\\d+)", RegexOption.IGNORE_CASE)
            .find(visibleText)?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toDoubleOrNull() } ?: return null
        return UsageNormalizer.normalize(UsageBucket("resets", "Banked resets", remaining = count, unit = UsageUnit.REQUESTS,
            confidence = Confidence.REPORTED, scope = QuotaScope.ACCOUNT))
    }

    internal fun parseReset(section: String, now: Long, localZone: ZoneId = ZoneId.systemDefault()): Long? {
        val line = section.lineSequence().firstOrNull { resetLabel.containsMatchIn(it) } ?: return null
        val iso = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})").find(line)?.value
        if (iso != null) return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        koreanResetTime.find(line)?.let { match ->
            val localNow = Instant.ofEpochMilli(now).atZone(localZone)
            val date = runCatching {
                LocalDate.of(
                    match.groupValues[1].toIntOrNull() ?: localNow.year,
                    match.groupValues[2].toIntOrNull() ?: localNow.monthValue,
                    match.groupValues[3].toIntOrNull() ?: localNow.dayOfMonth,
                )
            }.getOrNull()
            val rawHour = match.groupValues[5].toIntOrNull()
            val minute = match.groupValues[6].toIntOrNull()
            if (date != null && rawHour != null && minute != null && rawHour in 1..12 && minute in 0..59) {
                val hour = if (match.groupValues[4] == "오후") {
                    if (rawHour == 12) 12 else rawHour + 12
                } else if (rawHour == 12) 0 else rawHour
                var reset = ZonedDateTime.of(date, LocalTime.of(hour, minute), localZone)
                if (match.groupValues[1].isBlank() && !reset.toInstant().isAfter(Instant.ofEpochMilli(now))) {
                    reset = reset.plusDays(1)
                }
                return reset.toInstant().toEpochMilli()
            }
        }
        val dated = Regex("(\\d{4}-\\d{2}-\\d{2})(?:(?:[ T]| at )(\\d{2}:\\d{2})(?::(\\d{2}))?)?(?:\\s*(Z|UTC|[+-]\\d{2}:?\\d{2}))?", RegexOption.IGNORE_CASE).find(line)
        if (dated != null) {
            val date = dated.groupValues[1]
            val hm = dated.groupValues[2].ifBlank { "00:00" }
            val sec = dated.groupValues[3].ifBlank { "00" }
            val zone = dated.groupValues[4].ifBlank { "Z" }.let { if (it.equals("UTC", true)) "Z" else it }
            val stamp = "${date}T$hm:$sec${if (zone.startsWith("+") || zone.startsWith("-") || zone == "Z") zone else "Z"}"
            runCatching { OffsetDateTime.parse(stamp).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        }
        if (!Regex("\\bin\\b|후", RegexOption.IGNORE_CASE).containsMatchIn(line)) return null
        val hours = Regex("(\\d+)\\s*(?:hours?|hrs?|h\\b|시간)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:minutes?|mins?|m\\b|분)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val days = Regex("(\\d+)\\s*(?:days?|d\\b|일)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        if (days > 366 || hours > 8_784 || minutes > 527_040) return null
        val duration = days * 86_400_000 + hours * 3_600_000 + minutes * 60_000
        return if (duration in 1..(366L * 86_400_000)) Instant.ofEpochMilli(now).plusMillis(duration).toEpochMilli() else null
    }
}
