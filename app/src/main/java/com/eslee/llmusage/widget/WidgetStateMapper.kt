package com.eslee.llmusage.widget

import android.content.Context
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale

 data class WidgetAccountRow(val account: Account?, val provider: String, val values: List<WidgetValue>, val status: String?, val synced: String?, val providerUrl: String?)
 data class WidgetValue(val label: String, val text: String, val percent: Float?, val reset: String)
 /** Words a widget uses to state whether a number is consumed or left. */
 data class WidgetValueWords(val used: String, val remaining: String, val unknown: String)
 object WidgetStateMapper {
    suspend fun rows(context: Context, config: WidgetConfig, repository: com.eslee.llmusage.usage.UsageRepository? = null): List<WidgetAccountRow> {
        val graph = (context.applicationContext as UsageApplication).graph
        val source = repository ?: graph.repository
        val settings = graph.settings.current()
        val remaining = effectiveRemaining(config, settings.remaining)
        val words = WidgetValueWords(context.getString(R.string.used), context.getString(R.string.remaining), context.getString(R.string.widget_unknown))
        return config.selections.map { selection ->
            val account = source.account(selection.accountId)
            val snapshot = account?.let { source.latest(it.id) }
            val primary = snapshot?.let { UsagePresentation.primary(it,account?.primaryBucketId) }
            val buckets = when (selection.bucketSelector) {
                "ALL_PRIMARY" -> snapshot?.buckets.orEmpty()
                "PRIMARY" -> listOfNotNull(primary)
                else -> snapshot?.buckets.orEmpty().filter { it.id == selection.bucketSelector }
            }
            val status = when {
                account == null -> context.getString(R.string.widget_missing)
                account.lastErrorCode?.contains("AUTH") == true -> context.getString(R.string.widget_auth)
                account.lastErrorCode != null -> context.getString(R.string.widget_error)
                snapshot == null -> context.getString(R.string.widget_sync_needed)
                UsagePresentation.isStale(snapshot,staleAfterMillis = if(settings.staleHours > 0) settings.staleHours * 3_600_000L else if(snapshot.source == SnapshotSource.VISIBLE_PAGE) 21_600_000 else 7_200_000) -> context.getString(R.string.widget_stale)
                else -> null
            }
            WidgetAccountRow(account,account?.let { graph.registry.definition(it.providerId)?.displayName }.orEmpty(),buckets.map { bucket ->
                val display = display(bucket, remaining, words)
                WidgetValue(bucket.label, display.text, display.percent, reset(context,bucket.resetAt))
            },status,snapshot?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.fetchedAt)) },account?.let { graph.registry.definition(it.providerId)?.usageUrl })
        }
    }

    /**
     * A widget without its own choice must agree with the app screens rather than
     * silently showing the complement of what the provider page states.
     */
    fun effectiveRemaining(config: WidgetConfig, settingRemaining: Boolean): Boolean = config.remaining ?: settingRemaining

    internal data class ValueDisplay(val text: String, val percent: Float?)

    /**
     * The number alone is ambiguous: 24% used and 24% remaining are different
     * readings of the same bucket, so the meaning is always rendered with it.
     */
    internal fun display(bucket: UsageBucket, remaining: Boolean, words: WidgetValueWords, locale: Locale = Locale.getDefault()): ValueDisplay {
        val percent = if (remaining) UsageNormalizer.remainingPercent(bucket) else UsageNormalizer.usedPercent(bucket)
        val raw = if (remaining) bucket.remaining else bucket.used
        val word = if (remaining) words.remaining else words.used
        val text = percent?.let { String.format(locale,"%.0f%% %s",it,word) }
            ?: raw?.let { String.format(locale,"%.2f %s %s",it,bucket.unit.name,word) }
            ?: words.unknown
        // The gauge is a battery: it always fills with the capacity left, full when
        // unused and empty when spent, no matter whether the text shows used or left.
        val fill = UsageNormalizer.remainingPercent(bucket)?.toFloat()?.div(100)?.coerceIn(0f,1f)
        return ValueDisplay(text, fill)
    }

    fun reset(context: Context, resetAt: Long?): String {
        val remaining = UsagePresentation.countdown(resetAt) ?: return context.getString(R.string.widget_reset_unknown)
        if(remaining == 0L) return context.getString(R.string.widget_reset_passed)
        val minutes = (remaining + 59_999) / 60_000
        return if(minutes >= 60) context.getString(R.string.widget_reset_hours,minutes / 60) else context.getString(R.string.widget_reset_minutes,minutes)
    }
 }
