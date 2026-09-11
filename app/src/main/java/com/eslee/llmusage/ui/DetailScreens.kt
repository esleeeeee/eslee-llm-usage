package com.eslee.llmusage.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.webkit.WebViewCompat
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.core.web.ProfileSessions
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.usage.AccountOverview
import com.eslee.llmusage.usage.SyncLog
import com.eslee.llmusage.widget.WidgetConfig
import com.eslee.llmusage.widget.WidgetConfigStore
import com.eslee.llmusage.widget.WidgetConfigurationActivity
import com.eslee.llmusage.widget.WidgetStateMapper
import kotlinx.coroutines.launch

@Composable
internal fun DetailScreen(overview: AccountOverview, graph: AppGraph, settings: AppSettings, onRefresh: () -> Unit,
    onChange: (Account) -> Unit, onDelete: () -> Unit, onLogout: () -> Unit, onCredential: (String) -> Unit) {
    val account = overview.account
    val snapshot = overview.snapshot
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
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(account.alias, style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(graph.registry.definition(account.providerId)?.displayName, snapshot?.planName).joinToString(" · "))
            Text(statusLabel(account, snapshot, settings.staleHours), Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.last_sync, account.lastSuccessAt?.let(::timeLabel) ?: stringResource(R.string.never)), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onRefresh, enabled = account.enabled) { Text(stringResource(R.string.refresh)) }
        }
        if (snapshot == null) item { Text(stringResource(R.string.no_snapshot)) }
        items(snapshot?.buckets.orEmpty(), key = { it.id }) { bucket ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BucketContent(bucket, settings.remaining, detailed = true)
                TextButton(onClick = { onChange(account.copy(primaryBucketId = bucket.id)) }, enabled = account.primaryBucketId != bucket.id) {
                    Text(stringResource(if (account.primaryBucketId == bucket.id) R.string.primary else R.string.set_primary))
                }
            } }
        }
        snapshot?.extraCredits?.let { credit -> item { SectionTitle(R.string.extra_credits); Text("${credit.currency} ${credit.amount}", style = MaterialTheme.typography.headlineSmall) } }
        item {
            SectionTitle(R.string.history)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(days == 7, { days = 7 }, label = { Text(stringResource(R.string.days_7)) })
                FilterChip(days == 30, { days = 30 }, label = { Text(stringResource(R.string.days_30)) })
            }
        }
        val selectedBucket = snapshot?.let { UsagePresentation.primary(it, account.primaryBucketId)?.id }
        val recent = history.filter { it.fetchedAt >= System.currentTimeMillis() - days * 86_400_000L }.sortedBy { it.fetchedAt }
        val points = recent.mapNotNull { item -> item.buckets.find { it.id == selectedBucket }?.let { bucket ->
            (if (settings.remaining) UsageNormalizer.remainingPercent(bucket) else UsageNormalizer.usedPercent(bucket))?.let { item.fetchedAt to it }
        } }
        if (points.size >= 2) item {
            val color = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                val minimumTime = points.first().first
                val duration = (points.last().first - minimumTime).coerceAtLeast(1)
                val maximum = points.maxOf { it.second }.coerceAtLeast(100.0)
                points.zipWithNext().forEach { (a, b) ->
                    fun position(point: Pair<Long, Double>) = Offset(((point.first - minimumTime).toDouble() / duration * size.width).toFloat(), size.height - (point.second / maximum * size.height).toFloat())
                    drawLine(color, position(a), position(b), strokeWidth = 3.dp.toPx())
                }
            }
        }
        if (recent.isEmpty()) item { Text(stringResource(R.string.history_empty)) }
        items(recent.takeLast(30).asReversed(), key = { it.snapshotId }) { entry ->
            val bucket = entry.buckets.find { it.id == selectedBucket }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(timeLabel(entry.fetchedAt), style = MaterialTheme.typography.labelMedium)
                if (bucket != null) BucketContent(bucket, settings.remaining)
                HorizontalDivider(Modifier.padding(top = 8.dp))
            }
        }
        item {
            SectionTitle(R.string.connection_info)
            graph.registry.definition(account.providerId)?.let { Text(connectorLabel(it.status)) }
            Text(stringResource(if (account.authMode == AuthMode.WEB_PROFILE) R.string.consumer_sync_schedule else R.string.background))
            snapshot?.let { Text(stringResource(R.string.source, stringResource(when (it.source) {
                SnapshotSource.OFFICIAL_API -> R.string.api_source; SnapshotSource.VISIBLE_PAGE -> R.string.web_source
                SnapshotSource.DEMO -> R.string.demo; SnapshotSource.USER_ENTERED -> R.string.user_source
            }))) }
            TextButton(onClick = { rename = true }) { Text(stringResource(R.string.rename)) }
            if (account.authMode == AuthMode.API_KEY) TextButton(onClick = { credentialDialog = true }) { Text(stringResource(R.string.replace_credential)) }
            TextButton(onClick = { onChange(account.copy(enabled = !account.enabled)) }) { Text(stringResource(if (account.enabled) R.string.disable_account else R.string.enable_account)) }
            TextButton(onClick = onLogout) { Text(stringResource(R.string.logout)) }
            TextButton(onClick = { confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete_account)) }
        }
    }
}

@Composable
internal fun SectionTitle(resource: Int) { Text(stringResource(resource), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }

@Composable
internal fun ConfirmDelete(title: Int, body: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(title)) }, text = { Text(stringResource(body)) },
        confirmButton = { TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
internal fun WidgetsScreen(accounts: List<AccountOverview>, settings: AppSettings) {
    val context = LocalContext.current
    var widgets by remember { mutableStateOf(emptyList<WidgetConfig>()) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
            scope.launch { widgets = WidgetConfigStore(context).all() }
        } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { widgets = WidgetConfigStore(context).all() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(if (widgets.isEmpty()) R.string.no_widgets else R.string.widgets), style = MaterialTheme.typography.headlineSmall); Text(stringResource(R.string.widget_help), Modifier.padding(vertical = 12.dp)) }
        items(widgets, key = { it.appWidgetId }) { widget -> OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.widget_summary, widget.appWidgetId, widget.selections.size, stringArrayResource(R.array.widget_styles)[widget.style.ordinal]))
                widget.selections.take(2).forEach { selection ->
                    val overview = accounts.find { it.account.id == selection.accountId }
                    if (overview == null) Text(stringResource(R.string.widget_missing)) else {
                        Text(overview.account.alias, style = MaterialTheme.typography.titleMedium)
                        val bucket = overview.snapshot?.let { snapshot ->
                            snapshot.buckets.find { it.id == selection.bucketSelector } ?: UsagePresentation.primary(snapshot, overview.account.primaryBucketId)
                        }
                        if (bucket != null) BucketContent(bucket, WidgetStateMapper.effectiveRemaining(widget, settings.remaining)) else Text(stringResource(R.string.no_snapshot))
                        Text(statusLabel(overview.account, overview.snapshot, 0), style = MaterialTheme.typography.labelSmall)
                    }
                }
                TextButton(onClick = { context.startActivity(Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget.appWidgetId)) }) { Text(stringResource(R.string.edit)) }
            }
        } }
    }
}

@Composable
internal fun DiagnosticsScreen(graph: AppGraph) {
    val context = LocalContext.current
    var logs by remember { mutableStateOf(emptyList<SyncLog>()) }
    LaunchedEffect(Unit) { logs = graph.repository.logs() }
    val details = listOf(
        stringResource(R.string.android_version, Build.VERSION.RELEASE),
        stringResource(R.string.webview_version, WebViewCompat.getCurrentWebViewPackage(context)?.versionName ?: stringResource(R.string.unknown)),
        stringResource(R.string.multi_profile, stringResource(if (ProfileSessions.supported()) R.string.available else R.string.unavailable)),
        stringResource(R.string.schema_version, 1),
        stringResource(R.string.connector_version, "1"),
    )
    val exportTitle = stringResource(R.string.export_diagnostics)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(details) { Text(it) }
        item { TextButton(onClick = {
            val safeReport = details.joinToString("\n") + "\n\n" + logs.joinToString("\n") { "${timeLabel(it.startedAt)} ${it.resultCode}" }
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, safeReport), exportTitle))
        }) { Text(exportTitle) } }
        item { SectionTitle(R.string.sync_logs) }
        items(logs) { Text("${timeLabel(it.startedAt)} · ${it.resultCode}", style = MaterialTheme.typography.bodySmall) }
    }
}
