package com.eslee.llmusage.widget

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import com.eslee.llmusage.R
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.web.enableEdgeToEdgeContent
import com.eslee.llmusage.ui.ProviderMarks
import com.eslee.llmusage.ui.UsageTheme
import com.eslee.llmusage.ui.SectionTitle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class WidgetConfigurationActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeContent()
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val info = AppWidgetManager.getInstance(this).getAppWidgetInfo(id)
        // Both widgets share this screen and the same stored selection; only the preview and the save differ.
        val resets = info?.provider == ComponentName(this, ResetsWidgetReceiver::class.java)
        if (!resets && info?.provider != ComponentName(this, UsageWidgetReceiver::class.java)) { finish(); return }
        lifecycleScope.launch {
            val graph = (application as UsageApplication).graph
            val store = WidgetConfigStore(this@WidgetConfigurationActivity)
            val original = store.get(id)
            val accounts = graph.repository.accounts.first().filter { it.account.enabled }
            val options = AppWidgetManager.getInstance(this@WidgetConfigurationActivity).getAppWidgetOptions(id)
            val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(64)
            val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110).coerceAtLeast(64)
            val settings = graph.settings.flow.first()
            setContent {
                UsageTheme(when (settings.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }) {
                    var config by remember { mutableStateOf(original) }
                    var saving by remember { mutableStateOf(false) }
                    val preview by produceState<RemoteViews?>(null, config) {
                        val activity = this@WidgetConfigurationActivity
                        value = if (resets) renderResetsPreview(activity, config, WidgetStateMapper.resetSlots(activity, config), DpSize(width.dp, height.dp))
                        else renderWidgetPreview(activity, config, WidgetStateMapper.slots(activity, config), DpSize(width.dp, height.dp))
                    }
                    fun save() {
                        saving = true
                        lifecycleScope.launch {
                            try {
                                store.save(config)
                                val glanceId = GlanceAppWidgetManager(this@WidgetConfigurationActivity).getGlanceIdBy(id)
                                if (resets) ResetsGlanceWidget().update(this@WidgetConfigurationActivity, glanceId)
                                else UsageGlanceWidget().update(this@WidgetConfigurationActivity, glanceId)
                                setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                                finish()
                            } finally { saving = false }
                        }
                    }
                    Scaffold(
                        contentWindowInsets = WindowInsets.safeDrawing,
                        topBar = { TopAppBar(
                            title = { Text(stringResource(R.string.widget_configure)) },
                            navigationIcon = { IconButton(onClick = { finish() }) { Icon(Icons.Filled.Close, stringResource(R.string.widget_cancel)) } },
                        ) },
                        bottomBar = {
                            Surface(tonalElevation = 2.dp) {
                                Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { finish() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_cancel)) }
                                    Button(onClick = ::save, enabled = config.selections.isNotEmpty() && !saving, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_save)) }
                                }
                            }
                        },
                    ) { padding ->
                        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionTitle(R.string.widget_preview)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AndroidView(
                                    modifier = Modifier.width(width.dp).height(height.dp),
                                    factory = { context -> AppWidgetHostView(context).apply { setAppWidget(id, info) } },
                                    update = { view -> preview?.let { view.updateAppWidget(it) } },
                                )
                            }
                            Text(stringResource(R.string.widget_preview_note, width, height), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            SectionTitle(R.string.widget_accounts)
                            if (accounts.isEmpty()) Text(stringResource(R.string.no_accounts), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            accounts.forEach { overview ->
                                val account = overview.account
                                val selection = config.selections.find { it.accountId == account.id }
                                val provider = graph.registry.definition(account.providerId)
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(selection != null, onCheckedChange = { checked ->
                                                config = config.copy(selections = if (checked) config.selections + WidgetSelection(account.id) else config.selections.filterNot { it.accountId == account.id })
                                            })
                                            Icon(painterResource(ProviderMarks.icon(account.providerId)), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
                                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                                Text(account.alias, style = MaterialTheme.typography.titleSmall)
                                                Text(listOfNotNull(provider?.displayName, overview.snapshot?.planName).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                        val buckets = overview.snapshot?.buckets.orEmpty()
                                        if (selection != null && buckets.size > 1 && !resets) {
                                            val all = selection.bucketSelector == "ALL"
                                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                                                listOf(false to R.string.widget_primary, true to R.string.widget_all_buckets).forEachIndexed { index, (value, label) ->
                                                    SegmentedButton(
                                                        selected = all == value,
                                                        onClick = { config = config.copy(selections = config.selections.map { if (it.accountId == account.id) it.copy(bucketSelector = if (value) "ALL" else "PRIMARY") else it }) },
                                                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                                                    ) { Text(stringResource(label)) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            SectionTitle(R.string.widget_appearance)
                            Text(stringResource(R.string.widget_color_theme), style = MaterialTheme.typography.titleSmall)
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                WidgetTheme.entries.forEachIndexed { index, theme ->
                                    SegmentedButton(selected = config.theme == theme, onClick = { config = config.copy(theme = theme) }, shape = SegmentedButtonDefaults.itemShape(index, WidgetTheme.entries.size)) {
                                        Text(stringResource(when (theme) { WidgetTheme.SYSTEM -> R.string.system; WidgetTheme.LIGHT -> R.string.light; WidgetTheme.DARK -> R.string.dark }))
                                    }
                                }
                            }
                            Text(stringResource(R.string.widget_background), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                WidgetBackground.entries.forEachIndexed { index, background ->
                                    SegmentedButton(selected = config.background == background, onClick = { config = config.copy(background = background) }, shape = SegmentedButtonDefaults.itemShape(index, WidgetBackground.entries.size)) {
                                        Text(stringResource(when (background) { WidgetBackground.TRANSLUCENT -> R.string.widget_background_translucent; WidgetBackground.SOLID -> R.string.widget_background_solid; WidgetBackground.NONE -> R.string.widget_background_none }))
                                    }
                                }
                            }
                            if (!resets) Text(stringResource(R.string.widget_help_resize), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
        }
    }
}
