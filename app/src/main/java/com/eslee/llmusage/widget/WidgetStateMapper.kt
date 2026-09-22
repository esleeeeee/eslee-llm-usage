package com.eslee.llmusage.widget

import android.content.Context
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.ui.UsageLabels
import com.eslee.llmusage.ui.gauge.GaugeGeometry
import com.eslee.llmusage.usage.UsageRepository

/**
 * One ring on the widget. The number is always the share left, like the figure
 * on a battery, so a ring and its number can never contradict each other.
 */
data class WidgetSlot(
    val accountId: String?,
    val providerId: String?,
    val title: String,
    val remainingPercent: Double?,
    val number: String,
    val caption: String?,
    val warning: Boolean,
)

object WidgetStateMapper {
    suspend fun slots(context: Context, config: WidgetConfig, repository: UsageRepository? = null): List<WidgetSlot> {
        val graph = (context.applicationContext as UsageApplication).graph
        val source = repository ?: graph.repository
        val settings = graph.settings.current()
        return config.selections.flatMap { selection ->
            val account = source.account(selection.accountId)
                ?: return@flatMap listOf(WidgetSlot(null, null, context.getString(R.string.widget_missing), null, GaugeGeometry.numberText(null), context.getString(R.string.widget_missing_caption), true))
            val snapshot = source.latest(account.id)
            val primary = snapshot?.let { UsagePresentation.primary(it, account.primaryBucketId) }
            val buckets = when (selection.bucketSelector) {
                "ALL", "ALL_PRIMARY" -> snapshot?.buckets.orEmpty()
                "PRIMARY" -> listOfNotNull(primary)
                else -> snapshot?.buckets.orEmpty().filter { it.id == selection.bucketSelector }.ifEmpty { listOfNotNull(primary) }
            }
            val status = status(context, account, snapshot, settings.staleHours)
            if (buckets.isEmpty()) listOf(slot(context, account, null, status, labelled = false))
            else buckets.map { slot(context, account, it, status, labelled = buckets.size > 1) }
        }
    }

    /** The warning a ring must carry, or null when the value can be trusted. */
    fun status(context: Context, account: Account, snapshot: UsageSnapshot?, staleHours: Int, now: Long = System.currentTimeMillis()): String? = when {
        !account.enabled -> context.getString(R.string.widget_disabled)
        account.lastErrorCode?.contains("AUTH") == true -> context.getString(R.string.widget_auth)
        account.lastErrorCode != null -> context.getString(R.string.widget_error)
        snapshot == null -> context.getString(R.string.widget_sync_needed)
        UsagePresentation.isStale(snapshot, now, staleAfterMillis = if (staleHours > 0) staleHours * 3_600_000L else if (snapshot.source == SnapshotSource.VISIBLE_PAGE) 21_600_000 else 7_200_000) -> context.getString(R.string.widget_stale)
        else -> null
    }

    fun slot(context: Context, account: Account, bucket: UsageBucket?, status: String?, labelled: Boolean, now: Long = System.currentTimeMillis()): WidgetSlot {
        val remaining = bucket?.let { UsageNormalizer.remainingPercent(it) }
        val title = if (bucket != null && labelled) "${account.alias} · ${UsageLabels.bucketShort(context, bucket)}" else account.alias
        // A warning outranks the countdown: a stale reset time is not worth the line.
        val caption = status ?: bucket?.let { UsageLabels.resetCompact(context, it.resetAt, now) }
        return WidgetSlot(account.id, account.providerId, title, remaining, GaugeGeometry.numberText(remaining), caption, status != null)
    }
}
