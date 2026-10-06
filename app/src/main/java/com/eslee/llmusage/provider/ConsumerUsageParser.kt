package com.eslee.llmusage.provider

import com.eslee.llmusage.core.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.LocalDateTime
import java.util.Locale

/** Conservative parsing of text the user can see; no private API or credential extraction. */
object ConsumerUsageParser {
    const val VERSION = "1.2.6"
    /** A quota label opens a limit of its own; any other label only ends the section before it. */
    private data class Label(val id: String, val title: String, val pattern: Regex, val quota: Boolean = true)
    private fun label(id: String, title: String, pattern: String, quota: Boolean = true) = Label(id, title, Regex(pattern, RegexOption.IGNORE_CASE), quota)
    private val labels = mapOf(
        // Grok has one weekly limit and lists each product's share of it under the
        // limit. Those shares are not limits, so they close the weekly section
        // without becoming rings: a share with a number used to stand in for the
        // weekly figure whenever the page drew that figure out of innerText's reach.
        "grok" to listOf(label("weekly", "Weekly usage", "weekly(?:\\s+supergrok)?\\s+(?:usage|limit)|주간 사용량|매주.*한도|주간.*한도"),
            label("chat", "Chat", "^chat$", quota = false), label("imagine", "Imagine", "^imagine$", quota = false),
            label("voice", "Voice", "^voice$", quota = false), label("build", "Build", "^build$", quota = false), label("api", "API", "^api$", quota = false)),
        "claude" to listOf(label("session", "Current session", "current session|5[- ]hour(?: session)?|현재 세션|5시간"),
            label("weekly", "Weekly usage", "weekly(?: usage| limits?)?|all models|주간(?: 사용량| 한도)?|모든 모델"),
            label("sonnet", "Sonnet", "sonnet only|sonnet만")),
        "chatgpt" to listOf(
            label("session", "5-hour usage", "^(?:5[- ]hour(?:\\s+(?:usage(?: limit)?|limit))?|five[- ]hour|5h(?:\\s+(?:usage|limit))?|세션 사용량|5시간(?: (?:사용|단위))?(?: 한도)?)$"),
            label("weekly", "Weekly usage", "weekly usage(?: limit)?|weekly limit|주간 사용량(?: 한도)?|주간 한도|주간 사용 한도"),
            label("reserve", "Reserve usage", "gpt-reserve|reserve(?: usage| limit)?|spark"),
            label("session", "Codex usage", "^codex usage$|^코덱스 사용량$"),
            label("work", "Work usage", "work usage|작업 사용량"),
            label("model", "Model usage", "^(?:GPT[- ][\\w. -]+|o[134](?:[- ][\\w. -]+)?)(?:usage|limit|사용량|한도)?$")),
    )
    private val percentage = Regex("(?<![\\d.])(\\d{1,3}(?:\\.\\d+)?)\\s*%")
    private val ratio = Regex("(?<![\\d.])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)\\s*(?:/|of|중)\\s*(\\d+(?:,\\d{3})*(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    // "중고" is Grok's Korean machine translation of "used" on the usage page.
    private val usedMarker = Regex("\\bused\\b|\\butili[sz]ed\\b|\\bconsumed\\b|사용됨|사용한|중고", RegexOption.IGNORE_CASE)
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
        val lines = joinSplitFigures(visibleText.lineSequence().map(String::trim).filter(String::isNotBlank).toList())
        // The banked-reset section explains itself in quota words ("재설정을 사용해 5시간
        // 한도, 주간 한도 …를 복원하세요", "전체 재설정(주간 + 5시간)"). Taken as a label, that
        // made a 5-hour limit with no figure and a reset time borrowed from a credit's
        // expiry, on an account whose page has no 5-hour limit at all. Inside the section
        // a line about resets is never a quota; a real quota heading has no reset word.
        val resets = resetSectionRange(lines)
        val matches = lines.mapIndexedNotNull { index, line ->
            if (resets?.contains(index) == true && creditItem.containsMatchIn(line)) null
            else dictionary.firstOrNull { it.pattern.containsMatchIn(line) }?.let { index to it }
        }
        val buckets = matches.mapIndexedNotNull { index, (start, label) ->
            if (!label.quota) return@mapIndexedNotNull null
            val nextLabel = matches.getOrNull(index + 1)?.first ?: lines.size
            // A product's share below Grok's weekly limit ends the figures, not the limit:
            // the reset time is printed after the shares.
            val nextQuota = matches.drop(index + 1).firstOrNull { it.second.quota }?.first ?: lines.size
            val stop = Regex("Extra Usage Credits|upgrade|special offer|save \\d|업그레이드|할인", RegexOption.IGNORE_CASE)
            val end = minOf(nextLabel, start + 6)
            val sectionLines = lines.subList(start, end).takeWhile { !stop.containsMatchIn(it) }
            val section = sectionLines.joinToString("\n")
            val percentValues = parsePercentValues(sectionLines)
            val rawRatio = ratio.find(section)
            val used = rawRatio?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
            val limit = rawRatio?.groupValues?.get(2)?.replace(",", "")?.toDoubleOrNull()
            // The structural capture gives every node its own line, and a progress
            // bar's aria value or a CSS-drawn figure each take one, so the reset
            // time can sit well past the six lines the figures are read from. It
            // is looked for further down, still stopping at the next quota and
            // before a banked-reset offer, whose expiry is not this quota's reset.
            val resetWindow = lines.subList(start, minOf(nextQuota, start + 24))
                .takeWhile { !stop.containsMatchIn(it) && !resetsHeader.containsMatchIn(it) }
            val resetAt = parseReset(section, fetchedAt, localZone) ?: parseReset(resetWindow.joinToString("\n"), fetchedAt, localZone)
            if (percentValues.used == null && percentValues.remaining == null && used == null && resetAt == null) null
            else UsageNormalizer.normalize(UsageBucket(label.id, if (label.id == "model") lines[start] else label.title,
                used = used, limit = limit, unit = if (rawRatio != null) UsageUnit.REQUESTS else UsageUnit.PERCENT,
                usedPercent = percentValues.used, remainingPercent = percentValues.remaining, resetAt = resetAt,
                confidence = Confidence.REPORTED, scope = if (label.id in setOf("weekly", "session")) QuotaScope.ACCOUNT else QuotaScope.FEATURE))
        }.groupBy { it.id }.values.map { candidates ->
            candidates.maxByOrNull(::bucketEvidence) ?: candidates.first()
        }
        val credits = parseResetCredits(lines, fetchedAt, localZone)
        if (buckets.isEmpty() && credits == null) {
            val login = Regex("sign in to|log in to|continue with (google|apple)|이메일로 로그인|로그인하세요", RegexOption.IGNORE_CASE).containsMatchIn(visibleText)
            return ProviderResult.Failure(if (login) ProviderErrorCode.AUTH_REQUIRED else ProviderErrorCode.PARSE_FAILED,
                if (login) "서비스에 직접 로그인한 뒤 사용량 화면을 여세요." else "알려진 사용량 숫자나 명시적인 리셋 시각을 찾지 못했습니다. 이전 결과를 유지합니다.")
        }
        val creditLine = Regex(
            "(?:^|\\n)\\s*(?:Extra Usage Credits|Credits|남은 크레딧|추가 사용 크레딧|추가 크레딧)[ \\t:：]*(?:\\r?\\n[ \\t]*)?(?:US\\$|\\$|₩|€|£)?\\s*([0-9]+(?:[.,][0-9]+)?)",
            RegexOption.IGNORE_CASE,
        ).find(lines.joinToString("\n"))
        val credit = if (providerId in setOf("grok", "chatgpt")) {
            creditLine?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()?.let(::CreditBalance)
        } else null
        val plan = Regex("\\b(SuperGrok(?:\\s+(?:Plus|Heavy|Pro))?|Claude (?:Pro|Max)|ChatGPT (?:Plus|Pro|Business|Go))\\b", RegexOption.IGNORE_CASE).find(visibleText)?.value
        // The main quota is the first of the provider's windows that carries a figure,
        // so a page that has drawn only its weekly limit still has something to show.
        val order = if (providerId == "grok") listOf("weekly") else listOf("session", "weekly")
        val primary = order.firstOrNull { id -> buckets.any { it.id == id && hasNumbers(it) } }
            ?: order.firstOrNull { id -> buckets.any { it.id == id } } ?: order.first()
        return ProviderResult.Success(UsageSnapshot(accountId, providerId, buckets, fetchedAt, SnapshotSource.VISIBLE_PAGE,
            "공식 화면의 표시 텍스트 · parser $VERSION · 표시되지 않은 값은 알 수 없음", planName = plan, extraCredits = credit, resetCredits = credits.orEmpty(), resetCreditsKnown = credits != null,
            syncMode = SyncMode.FOREGROUND_ONLY,
            // Complete means the main quota has its figure; a side window without one does not hold a read back.
            status = if (buckets.any { it.id == primary && hasNumbers(it) }) SnapshotStatus.SUCCESS else SnapshotStatus.PARTIAL,
            primaryBucketId = primary, parserVersion = VERSION))
    }

    /**
     * Index of the provider's first quota label among [lines], or -1. Diagnostics
     * anchor on it instead of any line saying "usage": a page's sidebar can hold
     * the user's own conversation titles, and those must stay out of the trace.
     */
    fun quotaLabelIndex(providerId: String, lines: List<String>): Int {
        val quotas = labels[providerId]?.filter { it.quota } ?: return -1
        return lines.indexOfFirst { line -> quotas.any { it.pattern.containsMatchIn(line) } }
    }

    /** Lines of the banked-reset section, header through the line before the next section; null when there is none. */
    private fun resetSectionRange(lines: List<String>): IntRange? {
        val header = lines.indexOfFirst { resetsHeader.containsMatchIn(it) }
        if (header < 0) return null
        val end = (header + 1 until lines.size).firstOrNull { resetsStop.containsMatchIn(lines[it]) } ?: lines.size
        return header until end
    }

    /** The banked-reset section as the page lists it, header first, for diagnostics; null when the page has none. */
    fun resetSection(text: String): List<String>? {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val header = lines.indexOfFirst { resetsHeader.containsMatchIn(it) }
        if (header < 0) return null
        return listOf(lines[header]) + lines.drop(header + 1).takeWhile { !resetsStop.containsMatchIn(it) }
    }

    /** How long past its reset a quota's figure is still believed: servers can roll a window over a little late. */
    private const val LAPSE_GRACE = 60 * 60_000L

    /**
     * True when the main quota shows usage in a window that has already reset. No
     * current page says that; a page painting what it cached before the reset does.
     * Reported: Grok's figure never changed while every refresh counted as a success.
     * Nothing used, or no reset time, is never judged: an idle window can look like either.
     */
    fun lapsed(snapshot: UsageSnapshot, now: Long = System.currentTimeMillis()): Boolean {
        val primary = snapshot.buckets.firstOrNull { it.id == snapshot.primaryBucketId } ?: return false
        val resetAt = primary.resetAt ?: return false
        val used = UsageNormalizer.usedPercent(primary) ?: return false
        return used > 0.0 && resetAt < now - LAPSE_GRACE
    }

    private val figurePart = Regex("\\d+(?:\\.\\d+)?|\\.")
    private val figure = Regex("\\d{1,3}(?:\\.\\d+)?")

    /**
     * Animated counters give every character its own node, so the node walk reads
     * "52%" as "5", "2", "%" on lines of their own. Only the "2" beside the sign was
     * taken: Grok showed 2% used while its page said 52%. A run of digit lines that
     * ends at a line opening with the percent sign is one figure again; a number and
     * its sign drawn as two nodes ("35", "%") are the shortest such run.
     */
    private fun joinSplitFigures(lines: List<String>): List<String> {
        val joined = ArrayList<String>(lines.size)
        var index = 0
        while (index < lines.size) {
            var end = index
            while (end < lines.size && figurePart.matches(lines[end])) end++
            val digits = lines.subList(index, end).joinToString("")
            if (end > index && end < lines.size && lines[end].startsWith("%") && figure.matches(digits)) {
                joined += digits + lines[end]
                index = end + 1
            } else {
                joined += lines[index]
                index++
            }
        }
        return joined
    }

    fun hasNumbers(bucket: UsageBucket): Boolean =
        bucket.usedPercent != null || bucket.remainingPercent != null || bucket.used != null || bucket.remaining != null

    /** True when parsing found an actual figure, not just a label or a reset time. */
    fun carriesNumbers(result: ProviderResult): Boolean = result is ProviderResult.Success && result.snapshot.buckets.any(::hasNumbers)

    /** True when the account's main quota -- the one its ring shows -- has a figure. */
    fun primaryHasNumbers(result: ProviderResult): Boolean = result is ProviderResult.Success &&
        result.snapshot.buckets.any { it.id == result.snapshot.primaryBucketId && hasNumbers(it) }

    /**
     * A page is captured twice: as painted text and as a walk of its nodes, which
     * reaches figures drawn outside innerText. The painted text wins whenever it
     * holds the main quota's figure. The walk is taken when only it does, instead
     * of only when the painted text had no number anywhere: a breakdown line with
     * a number of its own used to keep the walk from ever being consulted.
     * Missing reset metadata is filled from the other capture of this same page.
     */
    fun parseBest(
        providerId: String,
        accountId: String,
        visibleText: String,
        richText: String?,
        fetchedAt: Long = System.currentTimeMillis(),
        localZone: ZoneId = ZoneId.systemDefault(),
    ): ProviderResult {
        val painted = parse(providerId, accountId, visibleText, fetchedAt, localZone)
        if (richText.isNullOrBlank() || richText == visibleText) return painted
        val walked = parse(providerId, accountId, richText, fetchedAt, localZone)
        val chosen = when {
            primaryHasNumbers(painted) -> painted
            primaryHasNumbers(walked) -> walked
            carriesNumbers(painted) -> painted
            carriesNumbers(walked) -> walked
            painted is ProviderResult.Success -> painted
            walked is ProviderResult.Success -> walked
            else -> painted
        }
        // Both captures are from this read. Preserve the chosen figures and fill
        // only missing reset times for the same quota; never import other quotas.
        val other = if (chosen === walked) painted else walked
        if (chosen is ProviderResult.Success && other is ProviderResult.Success) {
            val base = chosen.snapshot
            val supplement = other.snapshot
            val baseText = if (chosen === walked) richText else visibleText
            val supplementText = if (chosen === walked) visibleText else richText
            fun expiryEvidence(snapshot: UsageSnapshot, text: String): Int {
                val timed = resetSection(text).orEmpty().filter { expiryClock.containsMatchIn(it) }
                    .mapNotNull { parseExpiry(it, fetchedAt, localZone) }.toSet()
                return snapshot.resetCredits.sumOf { credit ->
                    if (credit.expiresAt == null) 0 else if (credit.expiresAt in timed) 2 else 1
                }
            }
            // A confirmed zero is evidence, not missing data. For nonempty lists,
            // take the more complete whole list, avoiding guessed item identities.
            val useOtherCredits = !base.resetCreditsKnown ||
                (base.resetCredits.isNotEmpty() && supplement.resetCreditsKnown &&
                    (supplement.resetCredits.size > base.resetCredits.size ||
                        (supplement.resetCredits.size == base.resetCredits.size &&
                            expiryEvidence(supplement, supplementText) > expiryEvidence(base, baseText))))
            return ProviderResult.Success(base.copy(
                buckets = base.buckets.map { bucket ->
                    bucket.copy(resetAt = bucket.resetAt ?: supplement.buckets.firstOrNull { it.id == bucket.id }?.resetAt)
                },
                resetCredits = if (useOtherCredits) supplement.resetCredits else base.resetCredits,
                resetCreditsKnown = if (useOtherCredits) supplement.resetCreditsKnown else base.resetCreditsKnown,
                extraCredits = base.extraCredits ?: supplement.extraCredits,
            ))
        }
        return chosen
    }

    private enum class PercentMeaning { USED, REMAINING, UNKNOWN }

    private data class PercentReading(val value: Double, val meaning: PercentMeaning, val line: Int)

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
                PercentReading(value, classifyPercent(lines, lineIndex, match), lineIndex)
            }.toList()
        }
        // A quota's own number is the first one under its label. Live pages carry
        // more percentages in the section than a hand-copied page shows, and
        // discarding all of them left the bucket with nothing but a reset time.
        val used = readings.filter { it.meaning == PercentMeaning.USED }.minByOrNull { it.line }?.value
        val remaining = readings.filter { it.meaning == PercentMeaning.REMAINING }.minByOrNull { it.line }?.value
        if (used != null && remaining != null) {
            return if (kotlin.math.abs(used + remaining - 100.0) <= 0.5) {
                PercentValues(used, remaining)
            } else {
                // Two contradictory explicit readings are safer as unknown.
                PercentValues(null, null)
            }
        }
        if (used != null || remaining != null) return PercentValues(used, remaining)

        // Position identifies the quota, but cannot tell used from remaining.
        return PercentValues(null, null)
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

    private val resetsHeader = Regex("사용량? 한도 (?:재설정|초기화)|usage limit resets?|banked resets?|resets? available", RegexOption.IGNORE_CASE)
    private val resetsStop = Regex("크레딧 사용 내역|추가 사용 크레딧|Extra Usage Credits|자동 충전|Auto[- ]?recharge|남은 크레딧", RegexOption.IGNORE_CASE)
    private val expiryMarker = Regex("만료|expir", RegexOption.IGNORE_CASE)
    private val expiryClock = Regex("\\d{1,2}:\\d{2}|\\d{1,2}시\\s*\\d{1,2}분")
    private val creditItem = Regex("재설정|초기화|reset", RegexOption.IGNORE_CASE)
    private val creditNoise = Regex("(?:재설정|초기화) 사용|use (?:a )?reset|(?:재설정|초기화)를 사용해|재설정을 사용해|사용량? 한도 (?:재설정|초기화)|usage limit resets?|banked resets?|resets? available", RegexOption.IGNORE_CASE)
    private val creditAvailable = Regex("재설정 가능|reset available", RegexOption.IGNORE_CASE)
    /** The button each banked reset carries; one per reset even where the page prints no expiry. */
    private val creditAction = Regex("^(?:초기화 사용(?: 전체 재설정(?:\\(주간 \\+ 5시간\\))?)?|재설정 사용|재설정 사용하기|use reset|use this reset|use)$", RegexOption.IGNORE_CASE)
    /** A count stated inside the section ("3개 사용 가능", "사용 가능 3개"), too generic to trust elsewhere on the page. */
    private val availableLabel = Regex("^(?:사용 가능|available)$", RegexOption.IGNORE_CASE)
    private val sectionCount = Regex("(\\d+)\\s*개\\s*(?:사용 가능|남음|보유)|사용 가능(?:한)?\\s*(?:재설정)?\\s*(\\d+)\\s*개?$|^(\\d+)\\s*(?:available|left)$", RegexOption.IGNORE_CASE)
    private val monthNames = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    /**
     * Banked resets. Codex lists each one under a header with its own expiry
     * ("사용량 한도 재설정 / 전체 재설정(주간 + 5시간) / 9월 21일 오전 7:41에 만료", or
     * "Usage limit resets / Available 2 / Full reset / Expires Oct 4, 1:58 AM"),
     * the older dashboard only counts them ("3 resets available"), and Grok
     * shows one that can be used ("사용 한도 재설정 / 재설정 가능 / 1일 후 만료").
     */
    internal fun parseResetCredits(lines: List<String>, now: Long, localZone: ZoneId): List<ResetCredit>? {
        val explicit = Regex("(\\d+)\\s+resets?\\s+available|\\bavailable\\s+(\\d+)\\b|재설정\\s*(\\d+)\\s*개", RegexOption.IGNORE_CASE)
            .find(lines.joinToString("\n"))?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() }
        val header = lines.indexOfFirst { resetsHeader.containsMatchIn(it) }
        // No section and no count: the page said nothing about reset credits, which is not the same as none.
        if (header < 0 && explicit == null) return null
        val section = if (header < 0) emptyList() else lines.subList(header + 1, lines.size).takeWhile { !resetsStop.containsMatchIn(it) }
        val credits = mutableListOf<ResetCredit>()
        var label: String? = null
        var lastLabel: String? = null
        var pending: ResetCredit? = null
        var precision = 0
        fun finishItem() {
            pending?.let { credits += it }
            pending = null
            precision = 0
            label = null
        }
        section.forEachIndexed { index, line ->
            when {
                creditAction.matches(line) -> {
                    // The same button can contribute its aria label then its text.
                    if (pending == null && label == null && index > 0 && creditAction.matches(section[index - 1])) {
                        return@forEachIndexed
                    }
                    if (pending == null) pending = ResetCredit(label ?: lastLabel)
                    finishItem()
                }
                expiryMarker.containsMatchIn(line) -> {
                    // One credit exposes both its full accessibility timestamp and
                    // its date-only caption. Prefer the clock within this item.
                    val candidates = listOfNotNull(line, section.getOrNull(index - 1), section.getOrNull(index + 1))
                        .filter { !creditItem.containsMatchIn(it) }
                        .mapNotNull { text -> parseExpiry(text, now, localZone)?.let { time ->
                            time to if (expiryClock.containsMatchIn(text)) 2 else 1
                        } }
                    val best = candidates.maxByOrNull { it.second }
                    if (pending == null || (best?.second ?: 0) > precision) {
                        pending = ResetCredit(label, best?.first)
                        precision = best?.second ?: 0
                    }
                }
                creditItem.containsMatchIn(line) && !creditNoise.containsMatchIn(line) -> {
                    finishItem()
                    label = line
                    lastLabel = line
                }
            }
        }
        finishItem()
        // A page may state a count beside an abbreviated list, or list a reset without
        // printing its expiry; the count, or one button per reset, is what the user has.
        val stated = section.firstNotNullOfOrNull { line -> sectionCount.find(line)?.groupValues?.drop(1)?.firstNotNullOfOrNull { it.toIntOrNull() } }
            // The live page draws the word and its number as separate nodes: "사용 가능 | 3".
            ?: section.indices.firstNotNullOfOrNull { i -> section[i].takeIf { availableLabel.matches(it) }?.let { section.getOrNull(i + 1)?.toIntOrNull() } }
        val buttons = section.count { creditAction.matches(it) }
        val authoritativeCount = stated ?: explicit
        val count = authoritativeCount ?: maxOf(credits.size, buttons)
        while (credits.size < count) credits += ResetCredit(lastLabel)
        if (credits.isEmpty() && authoritativeCount == null && section.any { creditAvailable.containsMatchIn(it) }) credits += ResetCredit(lastLabel)
        // A mounted header with a still-loading list does not establish zero.
        if (credits.isEmpty() && explicit == null && stated == null) return null
        return (if (authoritativeCount != null) credits.take(authoritativeCount) else credits)
            .sortedWith(compareBy(nullsLast()) { it.expiresAt })
    }

    private fun parseExpiry(line: String, now: Long, localZone: ZoneId): Long? {
        val zone = if (gmtToken.containsMatchIn(line)) explicitGmtOffset(line) ?: return null else localZone
        // The Korean Codex locale prints credit dates as "10. 22. 오후 7:29 GMT".
        val canonical = Regex("(?<![\\d.])(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(?=오전|오후)")
            .replace(line) { "${it.groupValues[1]}월 ${it.groupValues[2]}일 " }
        return koreanGmtTime(canonical) ?: monthDayTime(canonical, now, zone) ?: absoluteTime(canonical, now, zone) ?: relativeTime(canonical, now)
    }

    /** "9월 21일 오전 7:41" or "Oct 4, 1:58 AM": without a year, the year is the one that keeps the moment ahead. */
    private fun monthDayTime(line: String, now: Long, localZone: ZoneId): Long? {
        val localNow = Instant.ofEpochMilli(now).atZone(localZone)
        var year: Int? = null
        val month: Int
        val day: Int
        var hour = 0
        var minute = 0
        val korean = Regex("(?:(\\d{4})년\\s*)?(\\d{1,2})월\\s*(\\d{1,2})일(?:\\s*(오전|오후)\\s*(\\d{1,2}):(\\d{2}))?").find(line)
        val english = Regex("\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s+(\\d{1,2})(?:,?\\s*(\\d{4}))?(?:,?\\s*(?:at\\s*)?(\\d{1,2}):(\\d{2})\\s*(am|pm)?)?", RegexOption.IGNORE_CASE).find(line)
        if (korean != null) {
            year = korean.groupValues[1].toIntOrNull()
            month = korean.groupValues[2].toInt()
            day = korean.groupValues[3].toInt()
            val h = korean.groupValues[5].toIntOrNull()
            val m = korean.groupValues[6].toIntOrNull()
            if (h != null && m != null) {
                hour = if (korean.groupValues[4] == "오후") (if (h == 12) 12 else h + 12) else (if (h == 12) 0 else h)
                minute = m
            }
        } else if (english != null) {
            month = monthNames.indexOf(english.groupValues[1].take(3).lowercase()) + 1
            day = english.groupValues[2].toInt()
            year = english.groupValues[3].toIntOrNull()
            val h = english.groupValues[4].toIntOrNull()
            val m = english.groupValues[5].toIntOrNull()
            if (h != null && m != null) {
                val half = english.groupValues[6].lowercase()
                hour = when { half == "pm" && h != 12 -> h + 12; half == "am" && h == 12 -> 0; else -> h }
                minute = m
            }
        } else return null
        val moment = runCatching { ZonedDateTime.of(LocalDate.of(year ?: localNow.year, month, day), LocalTime.of(hour, minute), localZone) }.getOrNull() ?: return null
        val settled = if (year == null && moment.toInstant().isBefore(Instant.ofEpochMilli(now).minusSeconds(60L * 86_400))) moment.plusYears(1) else moment
        return settled.toInstant().toEpochMilli()
    }

    internal fun parseReset(section: String, now: Long, localZone: ZoneId = ZoneId.systemDefault()): Long? {
        // Even the short figure window can contain the beginning of a credit list.
        val sectionLines = section.lines().takeWhile { !resetsHeader.containsMatchIn(it) }
        // Codex supplies a full, explicitly zoned accessibility timestamp beside
        // the quota even when the painted Korean page omits the word "reset".
        if (sectionLines.firstOrNull()?.let { first -> labels.values.flatten().any { it.quota && it.pattern.containsMatchIn(first) } } == true) {
            sectionLines.drop(1).take(3).filterNot { expiryMarker.containsMatchIn(it) }
                .firstNotNullOfOrNull(::koreanGmtTime)?.let { return it }
        }
        val keywords = sectionLines.indices.filter { resetLabel.containsMatchIn(sectionLines[it]) }
        if (keywords.isEmpty()) return null
        // A page styles the moment and the word differently, so they are separate
        // nodes: "2026년 9월 25일 오후 4:39" beside "초기화", sometimes with a hidden
        // figure between them. An absolute time is trusted a few lines either
        // side of the word; a duration only right beside it, because "1일 후 만료"
        // under "재설정 가능" is a banked reset's expiry, not this quota's reset.
        for (at in keywords) {
            val around = sectionLines.subList(maxOf(0, at - 3), minOf(sectionLines.size, at + 4)).joinToString(" ")
            absoluteTime(around, now, localZone)?.let { return it }
            val beside = sectionLines.subList(maxOf(0, at - 1), minOf(sectionLines.size, at + 2)).joinToString(" ")
            val bankedExpiry = Regex("expir|만료|available|banked|재설정 가능", RegexOption.IGNORE_CASE)
            if (!bankedExpiry.containsMatchIn(beside)) {
                relativeTime(beside, now)?.let { return it }
            }
        }
        return absoluteTime(sectionLines.joinToString(" "), now, localZone)
    }

    private fun absoluteTime(line: String, now: Long, localZone: ZoneId): Long? {
        // An invalid explicit offset must not fall back to a date in the device zone.
        if (gmtToken.containsMatchIn(line) && explicitGmtOffset(line) == null) return null
        koreanGmtTime(line)?.let { return it }
        val iso = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})").find(line)?.value
        if (iso != null) return runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        // Grok's English UI uses the device's local time, e.g. September 25,
        // 2026 at 7:39 AM. Do not silently treat this zone-less display as UTC.
        val englishDate = Regex("\\b(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:tember)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)\\s+\\d{1,2},?\\s+\\d{4}\\s+(?:at\\s+)?\\d{1,2}:\\d{2}\\s+[AP]M", RegexOption.IGNORE_CASE).find(line)?.value
        if (englishDate != null) {
            val canonical = englishDate.replace(",", "").replace(Regex("\\s+at\\s+", RegexOption.IGNORE_CASE), " ").replace(Regex("\\s+"), " ")
            for (month in listOf("MMMM", "MMM")) {
                runCatching {
                    LocalDateTime.parse(canonical, java.time.format.DateTimeFormatterBuilder()
                        .parseCaseInsensitive().appendPattern("$month d uuuu h:mm a")
                        .toFormatter(Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT))
                        .atZone(localZone).toInstant().toEpochMilli()
                }.getOrNull()?.let { return it }
            }
        }
        // A month/day without a year is still a date, not today's clock time.
        // The node walk may put the date and clock on adjacent lines.
        if (Regex("\\d{1,2}월\\s*\\d{1,2}일").containsMatchIn(line)) {
            monthDayTime(line, now, localZone)?.let { return it }
        }
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
        return null
    }

    private val gmtToken = Regex("\\bGMT([^\\s]*)")

    private fun explicitGmtOffset(line: String): ZoneOffset? {
        val suffix = gmtToken.find(line)?.groupValues?.get(1) ?: return null
        if (suffix.isEmpty()) return ZoneOffset.UTC
        val offset = Regex("([+-])(\\d{1,2})(?::(\\d{2}))?").matchEntire(suffix) ?: return null
        val canonical = offset.groupValues[1] + offset.groupValues[2].padStart(2, '0') + ":" + offset.groupValues[3].ifEmpty { "00" }
        return runCatching { ZoneOffset.of(canonical) }.getOrNull()
    }

    private fun koreanGmtTime(line: String): Long? {
        val match = Regex("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일\\s+[월화수목금토일]요일\\s+(오전|오후)\\s*(\\d{1,2})시\\s*(\\d{1,2})분\\s*(\\d{1,2})초\\s+GMT[^\\s]*").find(line) ?: return null
        val zone = explicitGmtOffset(match.value) ?: return null
        val rawHour = match.groupValues[5].toInt()
        if (rawHour !in 1..12) return null
        val hour = rawHour % 12 + if (match.groupValues[4] == "오후") 12 else 0
        return runCatching {
            ZonedDateTime.of(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toInt(),
                hour, match.groupValues[6].toInt(), match.groupValues[7].toInt(), 0, zone).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun relativeTime(line: String, now: Long): Long? {
        if (!Regex("\\bin\\b|후", RegexOption.IGNORE_CASE).containsMatchIn(line)) return null
        val hours = Regex("(\\d+)\\s*(?:hours?|hrs?|h\\b|시간)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:minutes?|mins?|m\\b|분)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val days = Regex("(\\d+)\\s*(?:days?|d\\b|일)", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        if (days > 366 || hours > 8_784 || minutes > 527_040) return null
        val duration = days * 86_400_000 + hours * 3_600_000 + minutes * 60_000
        return if (duration in 1..(366L * 86_400_000)) Instant.ofEpochMilli(now).plusMillis(duration).toEpochMilli() else null
    }
}
