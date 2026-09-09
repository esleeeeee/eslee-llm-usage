package com.eslee.llmusage.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.eslee.llmusage.core.web.enableEdgeToEdgeContent
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeContent()
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,AppWidgetManager.INVALID_APPWIDGET_ID)
        if(id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        if(info?.provider != android.content.ComponentName(this,UsageWidgetReceiver::class.java)) { finish(); return }
        lifecycleScope.launch {
            val graph = (application as UsageApplication).graph
            val store = WidgetConfigStore(this@WidgetConfigurationActivity)
            val original = store.get(id)
            val accounts = graph.repository.accounts.first().filter { it.account.enabled }
            val options = AppWidgetManager.getInstance(this@WidgetConfigurationActivity).getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,180).coerceAtLeast(48)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,180).coerceAtLeast(48)
            setContent {
                MaterialTheme {
                    var config by remember { mutableStateOf(original) }
                    var saving by remember { mutableStateOf(false) }
                    val preview by produceState<List<WidgetAccountRow>>(emptyList(),config) { value = WidgetStateMapper.rows(this@WidgetConfigurationActivity,config) }
                    Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(R.string.widget_configure),style=MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.widget_accounts))
                            accounts.forEach { overview ->
                                val account = overview.account
                                val selection = config.selections.find { it.accountId == account.id }
                                Row(Modifier.fillMaxWidth()) {
                                    Checkbox(selection != null,onCheckedChange={ checked -> config = config.copy(selections=if(checked) config.selections + WidgetSelection(account.id) else config.selections.filterNot { it.accountId == account.id }) })
                                    Text(account.alias,Modifier.padding(top=12.dp))
                                }
                                if(selection != null) {
                                    val keys = listOf("PRIMARY","ALL_PRIMARY") + overview.snapshot?.buckets.orEmpty().map { it.id }
                                    val labels = listOf(stringResource(R.string.widget_primary),stringResource(R.string.widget_all_primary)) + overview.snapshot?.buckets.orEmpty().map { it.label }
                                    Choice(labels,keys.indexOf(selection.bucketSelector).coerceAtLeast(0)) { index -> config = config.copy(selections=config.selections.map { if(it.accountId == account.id) it.copy(bucketSelector=keys[index]) else it }) }
                                }
                            }
                            Text(stringResource(R.string.widget_style))
                            Choice(stringArrayResource(R.array.widget_styles).toList(),config.style.ordinal) { config=config.copy(style=WidgetStyle.entries[it]) }
                            Choice(listOf(stringResource(R.string.widget_used),stringResource(R.string.widget_remaining)),if(config.remaining) 1 else 0) { config=config.copy(remaining=it==1) }
                            Toggle(R.string.widget_provider,config.showProvider) { config=config.copy(showProvider=it) }
                            Toggle(R.string.widget_alias,config.showAlias) { config=config.copy(showAlias=it) }
                            Toggle(R.string.widget_reset,config.showReset) { config=config.copy(showReset=it) }
                            Toggle(R.string.widget_last_sync,config.showLastSync) { config=config.copy(showLastSync=it) }
                            Toggle(R.string.widget_status,config.showStatus) { config=config.copy(showStatus=it) }
                            Text(stringResource(R.string.widget_color_theme))
                            Choice(stringArrayResource(R.array.widget_color_themes).toList(),config.theme.ordinal) { config=config.copy(theme=WidgetTheme.entries[it]) }
                            Text(stringResource(R.string.widget_tap))
                            Choice(stringArrayResource(R.array.widget_taps).toList(),config.tap.ordinal) { config=config.copy(tap=WidgetTap.entries[it]) }
                            val size = WidgetLayoutResolver.resolve(width.toFloat(),height.toFloat())
                            Text(stringResource(R.string.widget_preview,size.name,width,height))
                            val remotePreview by produceState<android.widget.RemoteViews?>(null,config,preview) {
                                value = renderWidgetPreview(this@WidgetConfigurationActivity,config,preview,androidx.compose.ui.unit.DpSize(width.dp,height.dp))
                            }
                            androidx.compose.ui.viewinterop.AndroidView(
                                modifier=Modifier.width(width.dp).height(height.dp),
                                factory={ context -> android.appwidget.AppWidgetHostView(context).apply { setAppWidget(id,info) } },
                                update={ view -> remotePreview?.let { view.updateAppWidget(it) } },
                            )
                            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                TextButton(onClick={ finish() }) { Text(stringResource(R.string.widget_cancel)) }
                                Button(enabled=config.selections.isNotEmpty() && !saving,onClick={
                                    saving=true
                                    lifecycleScope.launch {
                                        try {
                                            store.save(config)
                                            UsageGlanceWidget().update(
                                                this@WidgetConfigurationActivity,
                                                GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(id),
                                            )
                                            setResult(RESULT_OK,Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id)); finish()
                                        } finally { saving=false }
                                    }
                                }) { Text(stringResource(R.string.widget_save)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable private fun Choice(labels: List<String>,selected: Int,onSelect:(Int)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={ expanded=true }) { Text(labels.getOrElse(selected){ labels.first() }) }
        DropdownMenu(expanded=expanded,onDismissRequest={ expanded=false }) { labels.forEachIndexed { index,label -> DropdownMenuItem(text={ Text(label) },onClick={ onSelect(index); expanded=false }) } }
    }
}
@Composable private fun Toggle(label: Int,checked: Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text(stringResource(label),Modifier.padding(top=12.dp)); Switch(checked,onCheckedChange=onChange) }
}


