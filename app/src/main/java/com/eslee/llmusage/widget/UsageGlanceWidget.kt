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
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.eslee.llmusage.R
import com.eslee.llmusage.ui.MainActivity
import com.eslee.llmusage.ui.ProviderMarks
import com.eslee.llmusage.ui.gauge.GaugeGeometry

class UsageGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigStore(context).get(appWidgetId)
        val slots = WidgetStateMapper.slots(context, config)
        provideContent { Content(context, config, slots) }
    }
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetConfigStore(context).delete(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

private class Palette(dark: Boolean, background: WidgetBackground) {
    val dark = dark
    val foreground = ColorProvider(if (dark) Color(0xFFF3F5F8) else Color(0xFF15181D))
    val muted = ColorProvider(if (dark) Color(0xA6F3F5F8) else Color(0x9915181D))
    val warning = ColorProvider(if (dark) Color(0xFFFFB92E) else Color(0xFFB07500))
    val panel: Color? = when (background) {
        WidgetBackground.TRANSLUCENT -> if (dark) Color(0xCC15181D) else Color(0xD9FFFFFF)
        WidgetBackground.SOLID -> if (dark) Color(0xFF15181D) else Color(0xFFFFFFFF)
        WidgetBackground.NONE -> null
    }
}

/**
 * Rings in a row, like the phone's own battery widget: the ring on top, the
 * provider mark inside it, the share left as a number in the opening at the
 * bottom, and the account's name underneath.
 */
@Composable
private fun Content(context: Context, config: WidgetConfig, slots: List<WidgetSlot>) {
    val size = LocalSize.current
    val grid = WidgetLayoutResolver.resolve(size.width.value, size.height.value, slots.size)
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val palette = Palette(config.theme == WidgetTheme.DARK || (config.theme == WidgetTheme.SYSTEM && night), config.background)
    var panel = GlanceModifier.fillMaxSize()
    palette.panel?.let { panel = panel.background(it).cornerRadius(20.dp) }
    panel = panel.padding(WidgetLayoutResolver.PADDING.dp).clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
    Box(panel, contentAlignment = Alignment.Center) {
        if (slots.isEmpty()) {
            Text(
                context.getString(R.string.widget_empty),
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(configIntent(context, config.appWidgetId))),
                TextStyle(color = palette.foreground, fontSize = 13.sp, textAlign = TextAlign.Center),
            )
        } else Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
            slots.take(grid.capacity).chunked(grid.columns).forEach { row ->
                Row(GlanceModifier.fillMaxWidth().defaultWeight(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
                    row.forEach { slot -> Ring(context, slot, grid, palette, config.appWidgetId, GlanceModifier.defaultWeight()) }
                    // Keep the columns of a short last row aligned with the rows above.
                    repeat(grid.columns - row.size) { Spacer(GlanceModifier.defaultWeight()) }
                }
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
    val titleSize = (grid.gauge * 0.2f).coerceIn(11f, 15f)
    val captionSize = (grid.gauge * 0.17f).coerceIn(9f, 13f)
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
        if (grid.showCaption && slot.caption != null) {
            Spacer(GlanceModifier.height(2.dp))
            Text(
                slot.caption,
                style = TextStyle(color = if (slot.warning) palette.warning else palette.muted, fontSize = captionSize.sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

fun configIntent(context: Context, id: Int): Intent =
    Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)

class UsageWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsageGlanceWidget() }

suspend fun updateWidgets(context: Context) {
    val active = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, UsageWidgetReceiver::class.java)).toSet()
    WidgetConfigStore(context).prune(active)
    UsageGlanceWidget().updateAll(context)
}

@OptIn(ExperimentalGlanceApi::class)
internal suspend fun renderWidgetPreview(context: Context, config: WidgetConfig, slots: List<WidgetSlot>, size: androidx.compose.ui.unit.DpSize): android.widget.RemoteViews {
    val preview = object : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent { Content(context, config, slots) }
        }
    }
    return preview.compose(context, size = size)
}
