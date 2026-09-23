package com.eslee.llmusage.widget

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.ExperimentalGlanceApi
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import com.eslee.llmusage.ui.MainActivity
import com.eslee.llmusage.ui.ProviderMarks

/**
 * A second, list-shaped widget: one line per account with how many banked
 * resets it holds and when the first of them lapses. It shares the ring
 * widget's configuration store and account selection.
 */
class ResetsGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigStore(context).get(appWidgetId)
        val rows = WidgetStateMapper.resetSlots(context, config)
        provideContent { ResetsContent(context, config, rows) }
    }
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetConfigStore(context).delete(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

class ResetsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = ResetsGlanceWidget() }

private const val ROW_HEIGHT = 30f
private const val ROW_PADDING = 6f

@Composable
private fun ResetsContent(context: Context, config: WidgetConfig, rows: List<ResetSlot>) {
    val size = LocalSize.current
    val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val dark = config.theme == WidgetTheme.DARK || (config.theme == WidgetTheme.SYSTEM && night)
    val foreground = ColorProvider(if (dark) Color(0xFFF3F5F8) else Color(0xFF15181D))
    val muted = ColorProvider(if (dark) Color(0xA6F3F5F8) else Color(0x9915181D))
    val warning = ColorProvider(if (dark) Color(0xFFFFB92E) else Color(0xFFB07500))
    val panelColor = when (config.background) {
        WidgetBackground.TRANSLUCENT -> if (dark) Color(0xCC15181D) else Color(0xD9FFFFFF)
        WidgetBackground.SOLID -> if (dark) Color(0xFF15181D) else Color(0xFFFFFFFF)
        WidgetBackground.NONE -> null
    }
    var panel = GlanceModifier.fillMaxSize()
    panelColor?.let { panel = panel.background(it).cornerRadius(20.dp) }
    panel = panel.padding(ROW_PADDING.dp)
    val capacity = ((size.height.value - 2 * ROW_PADDING) / ROW_HEIGHT).toInt().coerceAtLeast(1)
    Column(panel, verticalAlignment = Alignment.CenterVertically) {
        if (rows.isEmpty()) {
            Text(
                context.getString(R.string.widget_empty),
                GlanceModifier.fillMaxSize().clickable(actionStartActivity(configIntent(context, config.appWidgetId))),
                TextStyle(color = foreground, fontSize = 13.sp, textAlign = TextAlign.Center),
            )
        } else rows.take(capacity).forEach { row ->
            val target = if (row.accountId == null) configIntent(context, config.appWidgetId)
            else Intent(context, MainActivity::class.java).putExtra("accountId", row.accountId)
            Row(GlanceModifier.fillMaxWidth().height(ROW_HEIGHT.dp).clickable(actionStartActivity(target)), verticalAlignment = Alignment.CenterVertically) {
                Image(ImageProvider(ProviderMarks.icon(row.providerId)), null, GlanceModifier.size(16.dp), colorFilter = ColorFilter.tint(foreground))
                Spacer(GlanceModifier.width(8.dp))
                Text(row.title, GlanceModifier.defaultWeight(), TextStyle(color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(
                    context.getString(R.string.reset_credits_count, row.count),
                    style = TextStyle(color = foreground, fontSize = 14.sp, fontWeight = FontWeight.Bold), maxLines = 1,
                )
                Spacer(GlanceModifier.width(10.dp))
                Text(row.caption, style = TextStyle(color = if (row.warning) warning else muted, fontSize = 10.sp), maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalGlanceApi::class)
internal suspend fun renderResetsPreview(context: Context, config: WidgetConfig, rows: List<ResetSlot>, size: androidx.compose.ui.unit.DpSize): android.widget.RemoteViews {
    val preview = object : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context, id: GlanceId) {
            provideContent { ResetsContent(context, config, rows) }
        }
    }
    return preview.compose(context, size = size)
}
