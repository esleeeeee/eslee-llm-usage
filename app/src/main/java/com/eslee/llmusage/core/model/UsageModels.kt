package com.eslee.llmusage.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable enum class AuthMode { WEB_PROFILE, API_KEY, OAUTH_PKCE, NONE }
@Serializable enum class UsageUnit { REQUESTS, TOKENS, USD, PERCENT }
@Serializable enum class Confidence { EXACT, REPORTED, DERIVED, STALE, UNKNOWN }
@Serializable enum class SnapshotSource { OFFICIAL_API, VISIBLE_PAGE, DEMO, USER_ENTERED }
@Serializable enum class SyncMode { BACKGROUND, FOREGROUND_ONLY, MANUAL_ONLY }
@Serializable enum class SnapshotStatus { SUCCESS, PARTIAL }
@Serializable enum class QuotaScope { ACCOUNT, MODEL, FEATURE }

@Serializable
data class Account(
    val id: String,
    val providerId: String,
    val alias: String,
    val authMode: AuthMode,
    val profileName: String? = null,
    val teamId: String? = null,
    val enabled: Boolean = true,
    val primaryBucketId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSuccessAt: Long? = null,
    val lastAttemptAt: Long? = null,
    val lastErrorCode: String? = null,
)

@Serializable
data class UsageBucket(
    val id: String,
    val label: String,
    val used: Double? = null,
    val limit: Double? = null,
    val remaining: Double? = null,
    val unit: UsageUnit = UsageUnit.REQUESTS,
    val resetAt: Long? = null,
    val windowLabel: String? = null,
    val usedPercent: Double? = null,
    val remainingPercent: Double? = null,
    val confidence: Confidence = Confidence.REPORTED,
    val scope: QuotaScope = QuotaScope.ACCOUNT,
    val modelOrFeature: String? = null,
)

@Serializable data class CreditBalance(val amount: Double, val currency: String = "USD")

@Serializable
data class UsageSnapshot(
    val accountId: String,
    val providerId: String,
    val buckets: List<UsageBucket>,
    val fetchedAt: Long = System.currentTimeMillis(),
    val source: SnapshotSource = SnapshotSource.OFFICIAL_API,
    val note: String? = null,
    val snapshotId: String = UUID.randomUUID().toString(),
    val planName: String? = null,
    val accountLabel: String? = null,
    val extraCredits: CreditBalance? = null,
    val validUntil: Long? = null,
    val confidence: Confidence = Confidence.REPORTED,
    val syncMode: SyncMode = SyncMode.BACKGROUND,
    val status: SnapshotStatus = SnapshotStatus.SUCCESS,
    val primaryBucketId: String? = null,
    val parserVersion: String? = null,
)

object UsageNormalizer {
    private fun Double?.valid(): Double? = this?.takeIf { it.isFinite() && it >= 0 }

    fun normalize(bucket: UsageBucket): UsageBucket {
        val used = bucket.used.valid()
        val limit = bucket.limit.valid()
        val remaining = bucket.remaining.valid()
        val trusted = bucket.confidence in setOf(Confidence.EXACT, Confidence.REPORTED)
        val calculated = if (used != null && limit != null && limit > 0) (used / limit * 100).takeIf(Double::isFinite) else null
        val usedPercent = bucket.usedPercent.valid() ?: calculated
            ?: if (trusted) bucket.remainingPercent.valid()?.let { (100 - it).coerceAtLeast(0.0) } else null
        val remainingPercent = bucket.remainingPercent.valid()
            ?: if (trusted || calculated != null) usedPercent?.let { (100 - it).coerceAtLeast(0.0) } else null
        return bucket.copy(used = used, limit = limit, remaining = remaining,
            usedPercent = usedPercent, remainingPercent = remainingPercent)
    }

    fun remainingPercent(bucket: UsageBucket): Double? = normalize(bucket).remainingPercent
    fun usedPercent(bucket: UsageBucket): Double? = normalize(bucket).usedPercent
}

object UsagePresentation {
    fun primary(snapshot: UsageSnapshot, preferredBucketId: String? = null): UsageBucket? =
        snapshot.buckets.find { it.id == preferredBucketId }
            ?: snapshot.buckets.find { it.id == snapshot.primaryBucketId }
            ?: snapshot.buckets.firstOrNull { UsageNormalizer.usedPercent(it) != null || UsageNormalizer.remainingPercent(it) != null }
            ?: snapshot.buckets.firstOrNull()

    fun isStale(snapshot: UsageSnapshot, now: Long = System.currentTimeMillis(),
        staleAfterMillis: Long = if (snapshot.source == SnapshotSource.VISIBLE_PAGE) 21_600_000 else 7_200_000): Boolean =
        snapshot.fetchedAt > now || now - snapshot.fetchedAt >= staleAfterMillis.coerceAtLeast(0) ||
            snapshot.validUntil?.let { now >= it } == true

    fun countdown(resetAt: Long?, now: Long = System.currentTimeMillis()): Long? = resetAt?.let { (it - now).coerceAtLeast(0) }

    fun formatCountdown(resetAt: Long?, now: Long = System.currentTimeMillis()): String {
        val millis = countdown(resetAt, now) ?: return "Reset time unknown"
        if (millis == 0L) return "Reset time passed"
        val minutes = (millis + 59_999) / 60_000
        return if (minutes >= 60) "in ${minutes / 60}h ${minutes % 60}m" else "in ${minutes}m"
    }
}
