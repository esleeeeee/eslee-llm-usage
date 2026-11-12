package com.eslee.llmusage.ui

import android.content.Context
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.ResetCredit
import com.eslee.llmusage.core.model.UsageBucket
import com.eslee.llmusage.core.model.UsagePresentation
import java.text.DateFormat
import java.util.Date

/** Short, localised words for a quota and its reset, shared by the screens and the widget. */
object UsageLabels {
    /** "5시간" / "주간": the window a bucket covers, which is all a small label has room for. */
    fun bucketShort(context: Context, bucket: UsageBucket): String = when (bucket.id) {
        "session" -> context.getString(R.string.bucket_session)
        "weekly" -> context.getString(R.string.bucket_weekly)
        "resets" -> context.getString(R.string.bucket_resets)
        else -> bucket.label
    }

    data class Countdown(val days: Long, val hours: Long, val minutes: Long)

    fun countdown(resetAt: Long?, now: Long = System.currentTimeMillis()): Countdown? {
        val millis = UsagePresentation.countdown(resetAt, now) ?: return null
        val totalMinutes = (millis + 59_999) / 60_000
        return Countdown(totalMinutes / 1_440, totalMinutes % 1_440 / 60, totalMinutes % 60)
    }

    /** "3시간 20분 후 초기화" for a card; null when the page gave no time. */
    fun resetRelative(context: Context, resetAt: Long?, now: Long = System.currentTimeMillis()): String? {
        val left = countdown(resetAt, now) ?: return null
        return when {
            left.days == 0L && left.hours == 0L && left.minutes == 0L -> context.getString(R.string.reset_passed)
            left.days > 0 -> context.getString(R.string.reset_in_days_hours, left.days, left.hours)
            left.hours > 0 -> context.getString(R.string.reset_in_hours_minutes, left.hours, left.minutes)
            else -> context.getString(R.string.reset_in_minutes, left.minutes)
        }
    }

    /** "↻ 3시간": the same countdown at the width of a widget caption. */
    fun resetCompact(context: Context, resetAt: Long?, now: Long = System.currentTimeMillis()): String? {
        val left = countdown(resetAt, now) ?: return null
        return when {
            left.days == 0L && left.hours == 0L && left.minutes == 0L -> context.getString(R.string.widget_reset_passed)
            left.days >= 2 -> context.getString(R.string.widget_reset_days, left.days)
            left.days > 0 || left.hours > 0 -> context.getString(R.string.widget_reset_hours, left.days * 24 + left.hours)
            else -> context.getString(R.string.widget_reset_minutes, left.minutes)
        }
    }

    fun resetAbsolute(resetAt: Long): String = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(resetAt))

    /** "2일 5시간 남음" / "5시간 20분 남음" / "20분 남음": the countdown with room for two units. */
    fun resetLeft(context: Context, resetAt: Long?, now: Long = System.currentTimeMillis()): String? {
        val left = countdown(resetAt, now) ?: return null
        return when {
            left.days == 0L && left.hours == 0L && left.minutes == 0L -> context.getString(R.string.widget_reset_passed)
            left.days > 0 -> context.getString(R.string.widget_left_days_hours, left.days, left.hours)
            left.hours > 0 -> context.getString(R.string.widget_left_hours_minutes, left.hours, left.minutes)
            else -> context.getString(R.string.widget_left_minutes, left.minutes)
        }
    }

    /** "25일 16:39": when the reset happens, short enough for a widget line. */
    fun resetOn(context: Context, resetAt: Long): String =
        java.text.SimpleDateFormat(context.getString(R.string.widget_reset_on_pattern), java.util.Locale.getDefault()).format(Date(resetAt))

    /** "3일" / "5시간" / "20분": the largest unit that is not zero, for a caption or a summary. */
    fun countdownCompact(context: Context, at: Long?, now: Long = System.currentTimeMillis()): String? {
        val left = countdown(at, now) ?: return null
        return when {
            left.days == 0L && left.hours == 0L && left.minutes == 0L -> context.getString(R.string.expired)
            left.days > 0 -> context.getString(R.string.countdown_days, left.days)
            left.hours > 0 -> context.getString(R.string.countdown_hours, left.hours)
            else -> context.getString(R.string.countdown_minutes, left.minutes)
        }
    }

    /** "5일 12시간 남음" until a banked reset lapses, or "만료됨" once it has. */
    fun expiryLeft(context: Context, at: Long, now: Long = System.currentTimeMillis()): String {
        val left = countdown(at, now)
        return when {
            left == null || (left.days == 0L && left.hours == 0L && left.minutes == 0L) -> context.getString(R.string.expired)
            left.days > 0 -> context.getString(R.string.widget_left_days_hours, left.days, left.hours)
            left.hours > 0 -> context.getString(R.string.widget_left_hours_minutes, left.hours, left.minutes)
            else -> context.getString(R.string.widget_left_minutes, left.minutes)
        }
    }

    /** "10월 4일 (토) 오전 9:50": a moment within the coming weeks, in the phone's own style. */
    fun dayAndTime(at: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String =
        java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEEjmm"), locale).format(Date(at))

    private val creditDetail = Regex("\\s*[(（][^)）]*[)）]\\s*")
    private val creditWord = Regex("\\s*(?:초기화|재설정|reset)\\s*$", RegexOption.IGNORE_CASE)

    /**
     * What a banked reset restores, short enough for a widget line: "전체 초기화" → "전체",
     * "Fable 초기화" → "Fable", "전체 재설정(주간 + 5시간)" → "전체". A name that is only the
     * reset word keeps it.
     */
    fun creditKind(label: String?): String? {
        if (label == null) return null
        val plain = creditDetail.replace(label, " ").trim()
        return creditWord.replace(plain, "").trim().ifEmpty { plain.ifEmpty { label } }
    }

    /** One kind of banked reset an account holds: how many, and when the first of them lapses. */
    data class ResetKind(val name: String, val count: Int, val soonest: Long?)

    /**
     * The kinds of banked reset in [credits], soonest first, or an empty list when there is
     * only one kind. A reset without a name is shown as [unnamed], so it joins a named kind
     * that reads the same instead of appearing twice.
     */
    fun resetKinds(credits: List<ResetCredit>, unnamed: String): List<ResetKind> {
        val kinds = credits.groupBy { creditKind(it.label) ?: creditKind(unnamed) ?: unnamed }
        if (kinds.size < 2) return emptyList()
        return kinds.map { (name, list) -> ResetKind(name, list.size, list.mapNotNull { it.expiresAt }.minOrNull()) }
            .sortedWith(compareBy(nullsLast()) { it.soonest })
    }

    /**
     * "전체 29일 · Fable 117일": each kind of banked reset and when its first lapses, or null
     * when the account holds only one kind. Claude grants a full reset and a Fable-only one,
     * and the count alone hid which of the two was about to be lost.
     */
    fun resetKindsCompact(context: Context, credits: List<ResetCredit>, now: Long = System.currentTimeMillis()): String? {
        val kinds = resetKinds(credits, context.getString(R.string.reset_credit_default_label))
        if (kinds.isEmpty()) return null
        return kinds.joinToString(" · ") { kind ->
            listOfNotNull(
                kind.name,
                kind.count.takeIf { it > 1 }?.let { context.getString(R.string.reset_kind_count, it) },
                countdownCompact(context, kind.soonest, now),
            ).joinToString(" ")
        }
    }

    /** "리셋권 2개 · 3일 후 만료": how many banked resets an account holds and when the first one lapses. */
    fun resetCreditsSummary(context: Context, credits: List<ResetCredit>, now: Long = System.currentTimeMillis()): String? {
        if (credits.isEmpty()) return null
        resetKindsCompact(context, credits, now)?.let { return context.getString(R.string.reset_credits_summary_kinds, credits.size, it) }
        val soonest = credits.mapNotNull { it.expiresAt }.minOrNull()
            ?: return context.getString(R.string.reset_credits_summary_no_expiry, credits.size)
        return context.getString(R.string.reset_credits_summary, credits.size, countdownCompact(context, soonest, now))
    }
}
