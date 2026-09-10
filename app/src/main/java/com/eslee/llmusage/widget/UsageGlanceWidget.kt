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
import androidx.glance.*
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.*
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.AuthMode
import com.eslee.llmusage.core.web.ProviderWebActivity
import com.eslee.llmusage.ui.MainActivity

class UsageGlanceWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val config = WidgetConfigStore(context).get(appWidgetId)
        val rows = WidgetStateMapper.rows(context,config)
        provideContent { Content(context,config,rows) }
    }
    override suspend fun onDelete(context: Context, glanceId: GlanceId) {
        WidgetConfigStore(context).delete(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

@Composable
private fun Content(context: Context, config: WidgetConfig, rows: List<WidgetAccountRow>) {
    val dpSize = LocalSize.current
    val size = WidgetLayoutResolver.resolve(dpSize.width.value,dpSize.height.value)
    val dark = config.theme == WidgetTheme.DARK || (config.theme == WidgetTheme.SYSTEM && context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    val foreground = ColorProvider(if(dark) Color(0xFFF2F5FA) else Color(0xFF182333))
    val background = if(dark) Color(0xFF17202C) else Color(0xFFF4F7FC)
    val compact = size <= WidgetSize.S
    val overhead = if(size >= WidgetSize.L) 48 else 30
    val capacity = minOf(WidgetLayoutResolver.capacity(size), ((dpSize.height.value - overhead) / 42).toInt().coerceAtLeast(1))
    val rowHeight = (dpSize.height.value - overhead) / minOf(rows.size.coerceAtLeast(1),capacity)
    Column(GlanceModifier.fillMaxSize().background(background).padding(if(size == WidgetSize.XS) 8.dp else 10.dp)) {
        if(rows.isEmpty()) Text(context.getString(R.string.widget_empty),GlanceModifier.fillMaxSize().clickable(actionStartActivity(configIntent(context,config.appWidgetId))),TextStyle(color=foreground))
        else {
            if(size >= WidgetSize.L) Text("LLM Usage",style=TextStyle(color=foreground,fontSize=14.sp,fontWeight=FontWeight.Bold),maxLines=1)
            rows.take(capacity).forEach { row ->
                val missing = row.account == null
                val tap = if(config.tap == WidgetTap.AUTO) if(compact) WidgetTap.OPEN_ACCOUNT else WidgetTap.OPEN_APP else config.tap
                val target = when {
                    missing -> configIntent(context,config.appWidgetId)
                    tap == WidgetTap.OPEN_PROVIDER && row.account?.authMode == AuthMode.WEB_PROFILE -> Intent(context,ProviderWebActivity::class.java).putExtra("accountId",row.account?.id)
                    tap == WidgetTap.OPEN_PROVIDER && row.providerUrl?.startsWith("https://") == true -> Intent(Intent.ACTION_VIEW,android.net.Uri.parse(row.providerUrl))
                    tap == WidgetTap.REFRESH -> Intent(context,WidgetRefreshActivity::class.java).putExtra("accountId",row.account?.id)
                    else -> Intent(context,MainActivity::class.java).apply { if(tap == WidgetTap.OPEN_ACCOUNT) putExtra("accountId",row.account?.id) }
                }
                Column(GlanceModifier.fillMaxWidth().defaultWeight().clickable(actionStartActivity(target))) {
                    val identity = listOfNotNull(row.provider.takeIf { config.showProvider },row.account?.alias?.takeIf { config.showAlias }).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { row.account?.alias.orEmpty() }
                    Text(identity,style=TextStyle(color=foreground,fontSize=if(size == WidgetSize.XS) 10.sp else 12.sp),maxLines=1)
                    val values = row.values.take(if(size >= WidgetSize.M && rows.size == 1 && rowHeight > 150) 3 else if(size == WidgetSize.XL && rowHeight > 95) 2 else 1)
                    if(values.isEmpty()) Text(if(missing || size == WidgetSize.XS) "--" else context.getString(R.string.widget_unknown),style=TextStyle(color=foreground,fontSize=if(size == WidgetSize.XS) 14.sp else 18.sp),maxLines=1)
                    values.forEach { value ->
                      Column(GlanceModifier.fillMaxWidth()) {
                        val showLabel = size >= WidgetSize.M
                        Text((if(showLabel) "${value.label}  " else "") + value.text,style=TextStyle(color=foreground,fontSize=if(size == WidgetSize.XS) 14.sp else if(compact) 20.sp else 14.sp,fontWeight=FontWeight.Bold),maxLines=1)
                        if(size != WidgetSize.XS && rowHeight >= 58 && config.style !in setOf(WidgetStyle.NUMBER,WidgetStyle.LIST) && value.percent != null) LinearProgressIndicator(value.percent,GlanceModifier.fillMaxWidth().height(3.dp),color=ColorProvider(Color(0xFF4C82C7)),backgroundColor=ColorProvider(Color(0xFFCCD7E5)))
                        if(config.showReset && size != WidgetSize.XS && rowHeight >= 70) Text(value.reset,style=TextStyle(color=foreground,fontSize=10.sp),maxLines=1)
                    }
                      }
                    // Integrity warnings always remain visible, even when decorative status is disabled.
                    row.status?.let { Text("! $it",style=TextStyle(color=foreground,fontSize=if(size == WidgetSize.XS) 8.sp else 10.sp),maxLines=1) }
                    if(config.showLastSync && rowHeight >= 90 && size >= WidgetSize.L && row.synced != null) Text(context.getString(R.string.widget_synced,row.synced),style=TextStyle(color=foreground,fontSize=10.sp),maxLines=1)
                }
            }
            if(rows.size > capacity) Text(context.getString(R.string.widget_more,rows.size-capacity),GlanceModifier.clickable(actionStartActivity(Intent(context,MainActivity::class.java))),TextStyle(color=foreground,fontSize=10.sp),maxLines=1)
        }
    }
}
fun configIntent(context: Context,id: Int) = Intent(context,WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)
class UsageWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = UsageGlanceWidget() }
suspend fun updateWidgets(context: Context) {
    val active = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context,UsageWidgetReceiver::class.java)).toSet()
    WidgetConfigStore(context).prune(active)
    UsageGlanceWidget().updateAll(context)
}




@OptIn(ExperimentalGlanceApi::class)
internal suspend fun renderWidgetPreview(context: Context,config: WidgetConfig,rows: List<WidgetAccountRow>,size: androidx.compose.ui.unit.DpSize): android.widget.RemoteViews {
    val preview = object : GlanceAppWidget() {
        override val sizeMode = SizeMode.Exact
        override suspend fun provideGlance(context: Context,id: GlanceId) {
            provideContent { Content(context,config,rows) }
        }
    }
    return preview.compose(context,size=size)
}

