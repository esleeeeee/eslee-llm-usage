package com.eslee.llmusage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.eslee.llmusage.BuildConfig
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.app.UpdateChecker
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.Account
import com.eslee.llmusage.core.model.AuthMode
import com.eslee.llmusage.core.web.ProviderWebActivity
import com.eslee.llmusage.core.web.enableEdgeToEdgeContent
import com.eslee.llmusage.provider.ProviderResult
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.sync.SyncScheduler
import com.eslee.llmusage.usage.AccountOverview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

/** How long a release check result is trusted before the app asks GitHub again. */
private const val UPDATE_CHECK_INTERVAL = 6 * 3_600_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeContent()
        super.onCreate(savedInstanceState)
        setContent { UsageApp((application as UsageApplication).graph, intent.getStringExtra("accountId")) }
    }
}

/**
 * One list of accounts is the app. Everything else -- an account, adding one,
 * settings and what hangs off them -- is a screen pushed on top of it, so back
 * always returns to the rings.
 */
@Composable
internal fun UsageApp(graph: AppGraph, initialAccountId: String? = null) {
    val accounts by graph.repository.accounts.collectAsState(initial = emptyList())
    val settings by graph.settings.flow.collectAsState(initial = AppSettings())
    var stack by rememberSaveable { mutableStateOf(if (initialAccountId == null) "home" else "home>detail") }
    var selectedId by rememberSaveable { mutableStateOf(initialAccountId) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.operation_failed)
    val done = stringResource(R.string.saved)
    val validationFailed = stringResource(R.string.validation_failed)
    val route = stack.substringAfterLast('>')
    fun push(next: String) { stack = "$stack>$next" }
    fun pop() { if (stack.contains('>')) stack = stack.substringBeforeLast('>') }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snackbar.showSnackbar(failed) }
            finally { busy = false }
        }
    }
    fun openWeb(account: Account) {
        context.startActivity(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", account.id))
    }
    fun refresh(account: Account) {
        action {
            graph.repository.refresh(account.id)
            if (account.authMode == AuthMode.WEB_PROFILE && graph.repository.account(account.id)?.lastErrorCode == "AUTH_REQUIRED") openWeb(account)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, settings.intervalMinutes, settings.wifiOnly) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (SyncScheduler.allowsForeground(context, settings)) {
                    try { graph.repository.refreshAll() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { snackbar.showSnackbar(failed) }
                }
                delay(5 * 60_000L)
            }
        }
    }
    BackHandler(stack.contains('>')) { pop() }
    // A newer build on GitHub Releases is offered once every few hours, unless this version was skipped.
    var update by remember { mutableStateOf<UpdateChecker.Available?>(null) }
    LaunchedEffect(settings.updatePrompts) {
        if (!settings.updatePrompts) return@LaunchedEffect
        val stored = graph.settings.current()
        if (!stored.updatePrompts || System.currentTimeMillis() - stored.updateCheckedAt < UPDATE_CHECK_INTERVAL) return@LaunchedEffect
        val found = UpdateChecker().check(BuildConfig.VERSION_NAME)
        runCatching { graph.settings.update(graph.settings.current().copy(updateCheckedAt = System.currentTimeMillis())) }
        if (found != null && found.version != stored.updateSkipped) update = found
    }
    UsageTheme(when (settings.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }) {
        update?.let { found ->
            AlertDialog(
                onDismissRequest = { update = null },
                title = { Text(stringResource(R.string.update_available_title, found.version)) },
                text = { Text(stringResource(R.string.update_available_body, BuildConfig.VERSION_NAME)) },
                confirmButton = {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(found.apk ?: found.page))) }
                        update = null
                    }) { Text(stringResource(R.string.update_download)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            scope.launch { runCatching { graph.settings.update(graph.settings.current().copy(updateSkipped = found.version)) } }
                            update = null
                        }) { Text(stringResource(R.string.update_skip)) }
                        TextButton(onClick = { update = null }) { Text(stringResource(R.string.update_later)) }
                    }
                },
            )
        }
        Box(Modifier.fillMaxSize()) {
        when (route) {
            "add" -> AddAccountScreen(graph, busy, onBack = ::pop, onProviders = { push("providers") }) { provider, alias, secret, team, test ->
                action {
                    if (test) {
                        val temporary = Account("validation", provider.id, alias, provider.authMode, teamId = team)
                        if (graph.registry.adapter(provider.id)?.fetch(temporary, secret.orEmpty()) !is ProviderResult.Success) {
                            snackbar.showSnackbar(validationFailed)
                            return@action
                        }
                    }
                    val id = graph.repository.addAccount(provider.id, alias, secret, team)
                    keyboard?.hide()
                    selectedId = id
                    stack = "home>detail"
                    if (provider.authMode == AuthMode.WEB_PROFILE) {
                        context.startActivity(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", id).putExtra("newAccount", true))
                    } else if (test || provider.authMode == AuthMode.NONE) graph.repository.refresh(id)
                }
            }
            "providers" -> ProviderScreen(graph.registry.definitions, onBack = ::pop)
            "diagnostics" -> DiagnosticsScreen(graph, onBack = ::pop)
            "widgets" -> WidgetsScreen(accounts, graph, onBack = ::pop)
            "settings" -> SettingsScreen(settings, accounts, onChange = { action { graph.settings.update(it) } }, onBack = ::pop,
                onDiagnostics = { push("diagnostics") }, onProviders = { push("providers") }, onWidgets = { push("widgets") },
                onClear = { kind -> action {
                    when (kind) { 0 -> graph.repository.clearCredentials(apiOnly = true); 1 -> graph.repository.clearCredentials(webOnly = true); else -> graph.repository.clearData() }
                    snackbar.showSnackbar(done)
                } })
            "detail" -> {
                val selected = accounts.find { it.account.id == selectedId }
                if (selected != null) DetailScreen(selected, graph, settings, busy, onBack = ::pop,
                    onRefresh = { refresh(selected.account) }, onOpenWeb = { openWeb(selected.account) },
                    onChange = { updated -> action { graph.repository.updateAccount(updated) } },
                    onDelete = { action { graph.repository.deleteAccount(selected.account.id); stack = "home" } },
                    onLogout = { action { graph.repository.logout(selected.account.id) } },
                    onCredential = { secret -> action { graph.repository.setCredential(selected.account.id, secret); graph.repository.refresh(selected.account.id) } })
                else ScreenScaffold(stringResource(R.string.usage), onBack = ::pop) { padding -> Text(stringResource(R.string.no_accounts), Modifier.padding(padding).padding(24.dp)) }
            }
            else -> HomeScreen(accounts, graph, settings, busy,
                onOpen = { selectedId = it; push("detail") }, onAdd = { push("add") },
                onRefreshAll = { action { graph.repository.refreshAll() } },
                onSettings = { push("settings") }, onProviders = { push("providers") })
        }
        // Validation and save errors must also be visible on add/detail/settings.
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().imePadding().padding(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    accounts: List<AccountOverview>, graph: AppGraph, settings: AppSettings, busy: Boolean,
    onOpen: (String) -> Unit, onAdd: () -> Unit, onRefreshAll: () -> Unit, onSettings: () -> Unit, onProviders: () -> Unit,
) {
    ScreenScaffold(
        stringResource(R.string.home_title),
        actions = {
            if (accounts.isNotEmpty()) IconButton(onClick = onRefreshAll, enabled = !busy) { Icon(Icons.Outlined.Refresh, stringResource(R.string.refresh_all)) }
            IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, stringResource(R.string.settings)) }
        },
        floatingActionButton = { if (accounts.isNotEmpty()) FloatingActionButton(onClick = onAdd) { Icon(Icons.Filled.Add, stringResource(R.string.add_account)) } },
        busy = busy,
    ) { padding ->
        PullToRefreshBox(isRefreshing = busy, onRefresh = onRefreshAll, modifier = Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (accounts.isEmpty()) item {
                    EmptyState(stringResource(R.string.empty_title), stringResource(R.string.empty_description)) {
                        Button(onClick = onAdd) { Text(stringResource(R.string.add_account)) }
                        TextButton(onClick = onProviders) { Text(stringResource(R.string.provider_status)) }
                    }
                } else {
                    items(accounts, key = { it.account.id }) { overview ->
                        AccountCard(overview, graph.registry.definition(overview.account.providerId)?.displayName ?: overview.account.providerId,
                            settings.staleHours, onOpen = { onOpen(overview.account.id) })
                    }
                    item {
                        Text(
                            stringResource(R.string.last_sync, accounts.mapNotNull { it.account.lastSuccessAt }.maxOrNull()?.let(::timeLabel) ?: stringResource(R.string.never)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
