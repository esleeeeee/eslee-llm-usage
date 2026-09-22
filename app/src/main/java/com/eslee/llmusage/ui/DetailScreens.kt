package com.eslee.llmusage.ui

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.BuildConfig
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.core.web.ProfileSessions
import com.eslee.llmusage.core.web.WebTrace
import com.eslee.llmusage.provider.ProviderDefinition
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.ui.gauge.UsageGauge
import com.eslee.llmusage.usage.AccountOverview
import com.eslee.llmusage.usage.SyncLog
import com.eslee.llmusage.widget.WidgetConfig
import com.eslee.llmusage.widget.WidgetConfigStore
import com.eslee.llmusage.widget.WidgetSlot
import com.eslee.llmusage.widget.WidgetStateMapper
import com.eslee.llmusage.widget.configIntent
import kotlin.math.roundToInt

@Composable
internal fun DetailScreen(
    overview: AccountOverview, graph: AppGraph, settings: AppSettings, busy: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onOpenWeb: () -> Unit,
    onChange: (Account) -> Unit, onDelete: () -> Unit, onLogout: () -> Unit, onCredential: (String) -> Unit,
) {
    val account = overview.account
    val snapshot = overview.snapshot
    val provider = graph.registry.definition(account.providerId)
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var credentialDialog by remember { mutableStateOf(false) }
    var credential by remember { mutableStateOf("") }
    var alias by remember(account.alias) { mutableStateOf(account.alias) }
    var days by rememberSaveable { mutableIntStateOf(7) }
    var history by remember { mutableStateOf(emptyList<UsageSnapshot>()) }
    LaunchedEffect(account.id, snapshot?.snapshotId) { history = graph.repository.history(account.id) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text(stringResource(R.string.rename)) },
        text = { OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.alias)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onChange(account.copy(alias = alias.trim().ifBlank { account.alias })); rename = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.cancel)) } })
    if (confirm) ConfirmDelete(R.string.delete_account, R.string.delete_account_body, onDismiss = { confirm = false }) { confirm = false; onDelete() }
    if (credentialDialog) AlertDialog(onDismissRequest = { credentialDialog = false; credential = "" }, title = { Text(stringResource(R.string.credential)) },
        text = { OutlinedTextField(credential, { credential = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.credential)) }) },
        confirmButton = { TextButton(enabled = credential.isNotBlank(), onClick = { onCredential(credential.trim()); credential = ""; credentialDialog = false }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { credentialDialog = false; credential = "" }) { Text(stringResource(R.string.cancel)) } })

    val status = statusInfo(account, snapshot, settings.staleHours)
    ScreenScaffold(
        account.alias, onBack = onBack, busy = busy,
        actions = {
            IconButton(onClick = onRefresh, enabled = account.enabled && !busy) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh)) }
            if (account.authMode == AuthMode.WEB_PROFILE) IconButton(onClick = onOpenWeb) { Icon(Icons.Outlined.OpenInBrowser, stringResource(R.string.open_web)) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = { menu = false; rename = true })
                    DropdownMenuItem(text = { Text(stringResource(if (account.enabled) R.string.disable_account else R.string.enable_account)) }, onClick = { menu = false; onChange(account.copy(enabled = !account.enabled)) })
                    if (account.authMode == AuthMode.API_KEY) DropdownMenuItem(text = { Text(stringResource(R.string.replace_credential)) }, onClick = { menu = false; credentialDialog = true })
                    if (account.authMode == AuthMode.WEB_PROFILE) DropdownMenuItem(text = { Text(stringResource(R.string.logout)) }, onClick = { menu = false; onLogout() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete_account), color = MaterialTheme.colorScheme.error) }, onClick = { menu = false; confirm = true })
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderMark(account.providerId, size = 48.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(listOfNotNull(provider?.displayName, snapshot?.planName).joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.last_sync, account.lastSuccessAt?.let(::timeLabel) ?: stringResource(R.string.never)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                  }
                  StatusChip(status)
                }
            }
            if (snapshot == null) item { Text(stringResource(R.string.no_snapshot), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else item { SectionTitle(R.string.usage) }
            items(snapshot?.buckets.orEmpty(), key = { it.id }) { bucket ->
                val primary = UsagePresentation.primary(snapshot!!, account.primaryBucketId)?.id == bucket.id
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            UsageGauge(UsageNormalizer.remainingPercent(bucket), painterResource(ProviderMarks.icon(account.providerId)), size = 64.dp, warning = status.warning)
                            Box(Modifier.weight(1f)) { BucketContent(bucket, detailed = true) }
                        }
                        // Keep the action below the content so it cannot squeeze the usage
                        // down to a few characters on small screens or at large font sizes.
                        FilterChip(modifier = Modifier.align(Alignment.End), selected = primary, onClick = { if (!primary) onChange(account.copy(primaryBucketId = bucket.id)) }, label = { Text(stringResource(R.string.primary)) })
                    }
                }
            }
            snapshot?.extraCredits?.let { credit -> item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.extra_credits), style = MaterialTheme.typography.titleSmall)
                        Text(creditLabel(credit), style = MaterialTheme.typography.titleMedium)
                    }
                }
            } }
            item {
                SectionTitle(R.string.history)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(days == 7, { days = 7 }, label = { Text(stringResource(R.string.days_7)) })
                    FilterChip(days == 30, { days = 30 }, label = { Text(stringResource(R.string.days_30)) })
                }
            }
            val selectedBucket = snapshot?.let { UsagePresentation.primary(it, account.primaryBucketId)?.id }
            val recent = history.filter { it.fetchedAt >= System.currentTimeMillis() - days * 86_400_000L }.sortedBy { it.fetchedAt }
            val points = recent.mapNotNull { item -> item.buckets.find { it.id == selectedBucket }?.let { bucket -> UsageNormalizer.remainingPercent(bucket)?.let { item.fetchedAt to it } } }
            if (points.size >= 2) item { HistoryChart(points) }
            if (recent.isEmpty()) item { Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        recent.takeLast(12).asReversed().forEach { entry ->
                            val bucket = entry.buckets.find { it.id == selectedBucket }
                            val left = bucket?.let { UsageNormalizer.remainingPercent(it) }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(timeLabel(entry.fetchedAt), style = MaterialTheme.typography.bodyMedium)
                                Text(if (left != null) stringResource(R.string.remaining_percent, left.roundToInt()) else stringResource(R.string.unknown), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle(R.string.connection_info)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    provider?.let { Text(connectorLabel(it.status), style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(if (account.authMode == AuthMode.WEB_PROFILE) R.string.consumer_sync_schedule else R.string.background), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    snapshot?.let { Text(stringResource(R.string.source, stringResource(when (it.source) {
                        SnapshotSource.OFFICIAL_API -> R.string.api_source; SnapshotSource.VISIBLE_PAGE -> R.string.web_source
                        SnapshotSource.DEMO -> R.string.demo; SnapshotSource.USER_ENTERED -> R.string.user_source
                    })), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
    }
}

/** The share left over time: a line over a soft fill, with 0, 50 and 100 as guides. */
@Composable
private fun HistoryChart(points: List<Pair<Long, Double>>) {
    val line = MaterialTheme.colorScheme.primary
    val guide = MaterialTheme.colorScheme.outlineVariant
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(16.dp)) {
            val first = points.first().first
            val duration = (points.last().first - first).coerceAtLeast(1)
            fun position(point: Pair<Long, Double>) = Offset(
                ((point.first - first).toDouble() / duration * size.width).toFloat(),
                size.height - (point.second.coerceIn(0.0, 100.0) / 100.0 * size.height).toFloat(),
            )
            listOf(0f, 0.5f, 1f).forEach { level ->
                val y = size.height - level * size.height
                drawLine(guide, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val path = Path().apply {
                points.forEachIndexed { index, point -> val p = position(point); if (index == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            val area = Path().apply {
                addPath(path)
                lineTo(position(points.last()).x, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(area, line.copy(alpha = 0.15f))
            drawPath(path, line, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
internal fun ConfirmDelete(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun WidgetsScreen(accounts: List<AccountOverview>, graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    var widgets by remember { mutableStateOf(emptyList<Pair<WidgetConfig, List<WidgetSlot>>>()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    suspend fun load() { widgets = WidgetConfigStore(context).all().map { it to WidgetStateMapper.slots(context, it) } }
    // Configuration happens in another activity, so re-read on every return.
    LaunchedEffect(accounts) { load() }
    var resumed by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) resumed++ }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed) { load() }
    ScreenScaffold(stringResource(R.string.widgets), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.widget_help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (widgets.isEmpty()) item { Text(stringResource(R.string.no_widgets), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp)) }
            items(widgets, key = { it.first.appWidgetId }) { (widget, slots) ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            slots.forEach { slot ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
                                    UsageGauge(slot.remainingPercent, androidx.compose.ui.res.painterResource(ProviderMarks.icon(slot.providerId)), size = 64.dp, number = slot.number, warning = slot.warning)
                                    Text(slot.title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                    slot.caption?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = if (slot.warning) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.widget_summary, widget.appWidgetId, widget.selections.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { context.startActivity(configIntent(context, widget.appWidgetId)) }) { Text(stringResource(R.string.edit)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProviderScreen(providers: List<ProviderDefinition>, onBack: () -> Unit) {
    ScreenScaffold(stringResource(R.string.provider_status), onBack = onBack) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.local_first), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(providers, key = { it.id }) { provider ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ProviderMark(provider.id)
                            Column {
                                Text(provider.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(connectorLabel(provider.status) + " · " + syncModeLabel(provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        ProviderExplanation(provider)
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiagnosticsScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var logs by remember { mutableStateOf(emptyList<SyncLog>()) }
    var trace by remember { mutableStateOf(WebTrace.snapshot()) }
    LaunchedEffect(Unit) { logs = graph.repository.logs() }
    // The trace is written by a different activity, so re-read it on every return.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) trace = WebTrace.snapshot() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val details = listOf(
        // Which build produced a report is the first thing anyone reading one needs.
        stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
        stringResource(R.string.android_version, Build.VERSION.RELEASE),
        stringResource(R.string.webview_version, WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: stringResource(R.string.unknown)),
        stringResource(R.string.multi_profile, stringResource(if (ProfileSessions.supported()) R.string.available else R.string.unavailable)),
        stringResource(R.string.schema_version, 1),
        stringResource(R.string.connector_version, "1"),
    )
    val exportTitle = stringResource(R.string.export_diagnostics)
    val traceTitle = stringResource(R.string.web_trace)
    val emptyTrace = stringResource(R.string.web_trace_empty)
    fun export() {
        // The trace leads: it is the part someone is asked to send back.
        val safeReport = details.first() + "\n\n" +
            "$traceTitle (${trace.size})\n" + trace.joinToString("\n").ifBlank { emptyTrace } +
            "\n\n" + details.joinToString("\n") +
            "\n\n" + logs.take(20).joinToString("\n") { "${timeLabel(it.startedAt)} ${it.resultCode}" }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, safeReport), exportTitle))
    }
    ScreenScaffold(stringResource(R.string.diagnostics), onBack = onBack, actions = {
        IconButton(onClick = ::export) { Icon(Icons.Outlined.Share, exportTitle) }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) } }
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(R.string.web_trace, Modifier.weight(1f))
                    TextButton(onClick = { trace = WebTrace.snapshot() }) { Text(stringResource(R.string.refresh)) }
                    TextButton(onClick = { WebTrace.clear(); trace = WebTrace.snapshot() }) { Text(stringResource(R.string.web_trace_clear)) }
                }
            }
            if (trace.isEmpty()) item { Text(emptyTrace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(trace) { Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
            item { SectionTitle(R.string.sync_logs) }
            items(logs) { Text("${timeLabel(it.startedAt)} · ${it.resultCode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
