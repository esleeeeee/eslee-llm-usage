package com.eslee.llmusage.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.ColorFilter
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.eslee.llmusage.R
import com.eslee.llmusage.sync.SyncScheduler
import com.eslee.llmusage.ui.MainActivity
import com.eslee.llmusage.ui.ProviderMarks
import com.eslee.llmusage.ui.gauge.GaugeGeometry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Set by the refresh button and cleared when its full worker pass finishes. */
private val REFRESHING = booleanPreferencesKey("refreshing")
/** When the last full pass finished; the button stays green for a moment after it. */
private val REFRESHED_AT = longPreferencesKey("refreshed_at")
/** How long the button shows green once a pass is done. */
const val REFRESH_DONE_MILLIS = 3_000L

/** What the refresh button is telling the user. */
enum class RefreshState { IDLE, RUNNING, DONE }

fun refreshState(preferences: Preferences, now: Long = System.currentTimeMillis()): RefreshState = when {
    preferences[REFRESHING] == true -> RefreshState.RUNNING
    // The finish pass removes the mark itself; the time guard only covers a process killed in between.
    preferences[REFRESHED_AT]?.let { now - it in 0 until REFRESH_DONE_MILLIS * 4 } == true -> RefreshState.DONE
    else -> RefreshState.IDLE
}

class UsageGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigStore(context).get(appWidgetId)
        val slots = WidgetStateMapper.slots(context, config)
        provideContent { Content(context, config, slots, refreshState(currentState<Preferences>())) }
    }
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetConfigStore(context).delete(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

/** Marks the widget as refreshing, then asks WorkManager for one pass over every account. */
class RefreshAllAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        updateAppWidgetState(context, glanceId) { it[REFRESHING] = true }
        try {
            SyncScheduler.refreshAll(context)
            UsageGlanceWidget().update(context, glanceId)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                updateAppWidgetState(context, glanceId) { it.remove(REFRESHING) }
                UsageGlanceWidget().update(context, glanceId)
            }
            throw error
        }
    }
}

private class Palette(dark: Boolean, background: WidgetBackground) {
    val dark = dark
    val foreground = ColorProvider(if (dark) Color(0xFFF3F5F8) else Color(0xFF15181D))
    val muted = ColorProvider(if (dark) Color(0xA6F3F5F8) else Color(0x9915181D))
    val warning = ColorProvider(if (dark) Color(0xFFFFB92E) else Color(0xFFB07500))
    val done = ColorProvider(Color(GaugeGeometry.fillArgb(GaugeGeometry.Level.HIGH, dark)))
    val panel: Color? = when (background) {
        WidgetBackground.TRANSLUCENT -> if (dark) Color(0xCC15181D) else Color(0xD9FFFFFF)
        WidgetBackground.SOLID -> if (dark) Color(0xFF15181D) else Color(0xFFFFFFFF)
        WidgetBackground.NONE -> null
    }
}

/** Width kept free on the right for the refresh button, so it never sits on a ring. */
private const val REFRESH_COLUMN = 48f
/** Narrower widgets give their whole width to rings; their rings open the app instead. */
private const val REFRESH_MIN_WIDTH = 200f

/**
 * Rings in a row, like the phone's own battery widget: the ring on top, the
 * provider mark inside it, the share left as a number in the opening at the
 * bottom, the account's name and its reset countdown underneath. One ring per
 * launcher cell across; more accounts open more rows.
 */
@Composable
private fun Content(context: Context, config: WidgetConfig, slots: List<WidgetSlot>, refresh: RefreshState) {
    val size = LocalSize.current
    val showRefresh = size.width.value >= REFRESH_MIN_WIDTH
    val grid = WidgetLayoutResolver.resolve(
        size.width.value - if (showRefresh) REFRESH_COLUMN else 0f, size.height.value, slots.size,
        columns = WidgetLayoutResolver.cells(size.width.value),
    )
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val palette = Palette(config.theme == WidgetTheme.DARK || (config.theme == WidgetTheme.SYSTEM && night), config.background)
    var panel = GlanceModifier.fillMaxSize()
    palette.panel?.let { panel = panel.background(it).cornerRadius(20.dp) }
    panel = panel.padding(WidgetLayoutResolver.PADDING.dp)
    Row(panel, verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.defaultWeight().fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (slots.isEmpty()) {
                Text(
                    context.getString(R.string.widget_empty),
                    GlanceModifier.fillMaxSize().clickable(actionStartActivity(configIntent(context, config.appWidgetId))),
                    TextStyle(color = palette.foreground, fontSize = 13.sp, textAlign = TextAlign.Center),
                )
            } else Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                slots.take(grid.capacity).chunked(grid.columns).forEach { row ->
                    // Rings are cell-sized, so a short last row is centred without changing their size.
                    Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                        row.forEach { slot -> Ring(context, slot, grid, palette, config.appWidgetId, GlanceModifier.width(grid.slotWidth.dp)) }
                    }
                }
            }
        }
        if (showRefresh) Column(GlanceModifier.width(REFRESH_COLUMN.dp).fillMaxHeight(), verticalAlignment = Alignment.Top, horizontalAlignment = Alignment.End) {
            // The icon is small, but its whole touch target belongs to refresh. A root
            // activity click used to turn near-icon taps into an unexpected app launch.
            Box(GlanceModifier.size(REFRESH_COLUMN.dp).clickable(actionRunCallback<RefreshAllAction>()), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(R.drawable.ic_refresh),
                    context.getString(when (refresh) {
                        RefreshState.RUNNING -> R.string.widget_refreshing
                        RefreshState.DONE -> R.string.widget_refreshed
                        RefreshState.IDLE -> R.string.widget_refresh
                    }),
                    GlanceModifier.size(18.dp),
                    // Amber while the pass runs, green for a moment when it is done, then quiet again.
                    colorFilter = ColorFilter.tint(when (refresh) {
                        RefreshState.RUNNING -> palette.warning
                        RefreshState.DONE -> palette.done
                        RefreshState.IDLE -> palette.muted
                    }),
                )
            }
        }
    }
}

@Composable
private fun Ring(context: Context, slot: WidgetSlot, grid: WidgetGrid, palette: Palette, appWidgetId: Int, modifier: GlanceModifier) {
    val density = context.resources.displayMetrics.density
    val ring = GaugeBitmap.render((grid.gauge * density).toInt(), slot.remainingPercent, palette.dark, slot.warning)
    val target = if (slot.accountId == null) configIntent(context, appWidgetId)
    else Intent(context, MainActivity::class.java).putExtra("accountId", slot.accountId)
    val titleSize = (grid.gauge * 0.2f).coerceIn(11f, 12f)
    val captionSize = (grid.gauge * 0.17f).coerceIn(9f, 10f)
    Column(modifier.clickable(actionStartActivity(target)), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Box(GlanceModifier.size(grid.gauge.dp), contentAlignment = Alignment.BottomCenter) {
            Image(ImageProvider(ring), null, GlanceModifier.fillMaxSize())
            Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    ImageProvider(ProviderMarks.icon(slot.providerId)), null,
                    GlanceModifier.size((grid.gauge * GaugeGeometry.MARK_FRACTION).dp),
                    colorFilter = ColorFilter.tint(palette.foreground),
                )
            }
            Text(
                slot.number,
                style = TextStyle(color = palette.foreground, fontSize = (grid.gauge * GaugeGeometry.NUMBER_FRACTION).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
        if (grid.showTitle) {
            Spacer(GlanceModifier.height(WidgetLayoutResolver.RING_GAP.dp))
            Text(
                slot.title,
                style = TextStyle(color = palette.foreground, fontSize = titleSize.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
        if (grid.showCaption) {
            // A ring without a countdown keeps the line, so every ring in the row sits at the same height.
            Spacer(GlanceModifier.height(WidgetLayoutResolver.CAPTION_GAP.dp))
            Text(
                slot.caption ?: " ",
                style = TextStyle(color = if (slot.warning) palette.warning else palette.muted, fontSize = captionSize.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

fun configIntent(context: Context, id: Int): Intent =
    Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

class UsageWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsageGlanceWidget() }

/** Every widget class this app installs; their configurations share one store keyed by widget id. */
private val widgetReceivers = listOf(UsageWidgetReceiver::class.java, ResetsWidgetReceiver::class.java)

/** Redraws every widget from the database; called after each account is saved. */
suspend fun updateWidgets(context: Context) {
    runCatching {
        val manager = AppWidgetManager.getInstance(context)
        val active = widgetReceivers.flatMap { manager.getAppWidgetIds(ComponentName(context, it)).toList() }.toSet()
        WidgetConfigStore(context).prune(active)
    }
    UsageGlanceWidget().updateAll(context)
    ResetsGlanceWidget().updateAll(context)
}

/**
 * Only the manually requested full pass owns the refresh indicator. The button
 * turns green when the pass is over and goes quiet a few seconds later. Glance
 * redraws a widget when its state changes, not when time passes, so the quiet
 * button needs the mark removed from the state, not merely a second update:
 * CI caught the button staying green after a pass when only time had moved.
 */
suspend fun finishWidgetRefresh(context: Context) {
    val ids = runCatching { GlanceAppWidgetManager(context).getGlanceIds(UsageGlanceWidget::class.java) }.getOrDefault(emptyList())
    runCatching {
        ids.forEach { id ->
            updateAppWidgetState(context, id) {
                it.remove(REFRESHING)
                it[REFRESHED_AT] = System.currentTimeMillis()
            }
        }
    }
    UsageGlanceWidget().updateAll(context)
    delay(REFRESH_DONE_MILLIS)
    runCatching { ids.forEach { id -> updateAppWidgetState(context, id) { it.remove(REFRESHED_AT) } } }
    UsageGlanceWidget().updateAll(context)
}

@OptIn(ExperimentalGlanceApi::class)
internal suspend fun renderWidgetPreview(context: Context, config: WidgetConfig, slots: List<WidgetSlot>, size: androidx.compose.ui.unit.DpSize): android.widget.RemoteViews {
    val preview = object : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent { Content(context, config, slots, RefreshState.IDLE) }
        }
    }
    return preview.compose(context, size = size)
}
