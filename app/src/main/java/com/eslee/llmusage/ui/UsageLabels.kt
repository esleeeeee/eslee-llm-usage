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

    /** "리셋권 2개 · 3일 후 만료": how many banked resets an account holds and when the first one lapses. */
    fun resetCreditsSummary(context: Context, credits: List<ResetCredit>, now: Long = System.currentTimeMillis()): String? {
        if (credits.isEmpty()) return null
        val soonest = credits.mapNotNull { it.expiresAt }.minOrNull()
            ?: return context.getString(R.string.reset_credits_summary_no_expiry, credits.size)
        return context.getString(R.string.reset_credits_summary, credits.size, countdownCompact(context, soonest, now))
    }
}
