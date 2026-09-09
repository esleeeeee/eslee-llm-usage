package com.eslee.llmusage.ui

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.app.UsageApplication
import com.eslee.llmusage.core.model.*
import com.eslee.llmusage.core.web.ProfileSessions
import com.eslee.llmusage.core.web.ProviderWebActivity
import com.eslee.llmusage.provider.*
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.usage.AccountOverview
import com.eslee.llmusage.usage.SyncLog
import com.eslee.llmusage.widget.WidgetConfig
import com.eslee.llmusage.widget.WidgetConfigStore
import com.eslee.llmusage.widget.WidgetConfigurationActivity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { UsageApp((application as UsageApplication).graph, intent.getStringExtra("accountId")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UsageApp(graph: AppGraph, initialAccountId: String? = null) {
    val accounts by graph.repository.accounts.collectAsState(initial = emptyList())
    val settings by graph.settings.flow.collectAsState(initial = AppSettings())
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var route by rememberSaveable { mutableStateOf(if (initialAccountId == null) "home" else "detail") }
    var selectedId by rememberSaveable { mutableStateOf(initialAccountId) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.operation_failed)
    val done = stringResource(R.string.saved)
    val validationFailed = stringResource(R.string.validation_failed)
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() } catch (_: Exception) { snackbar.showSnackbar(failed) } finally { busy = false }
        }
    }
    fun refresh(account: Account) {
        if (account.authMode == AuthMode.WEB_PROFILE) context.startActivity(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", account.id))
        else action { graph.repository.refresh(account.id) }
    }
    LaunchedEffect(Unit) { runCatching { graph.repository.refreshAll() } }
    BackHandler(route != "home") { route = "home" }
    UsageTheme(when (settings.theme) { "DARK" -> true; "LIGHT" -> false; else -> isSystemInDarkTheme() }) {
        val tabs = listOf(R.string.dashboard, R.string.accounts, R.string.widgets, R.string.settings)
        Scaffold(
            topBar = { TopAppBar(title = { Text(stringResource(when (route) {
                "add" -> R.string.add_account; "providers" -> R.string.provider_status; "diagnostics" -> R.string.diagnostics
                "detail" -> R.string.usage; else -> tabs[tab]
            })) }, navigationIcon = { if (route != "home") TextButton(onClick = { route = "home" }) { Text(stringResource(R.string.back)) } },
                actions = { if (route == "home" && tab < 2) {
                    TextButton(onClick = { route = "add" }) { Text(stringResource(R.string.add_account)) }
                } }) },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (route == "home") NavigationBar { tabs.forEachIndexed { index, label ->
                NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Text(listOf("◫", "◎", "▦", "⚙")[index]) }, label = { Text(stringResource(label)) })
            } } },
        ) { padding ->
            Column(Modifier.padding(padding)) {
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (route) {
                    "add" -> AddAccountScreen(graph, busy, onAdd = { provider, alias, secret, team, test -> action {
                        if (test) {
                            val temporary = Account("validation", provider.id, alias, provider.authMode, teamId = team)
                            if (graph.registry.adapter(provider.id)?.fetch(temporary, secret.orEmpty()) !is ProviderResult.Success) {
                                snackbar.showSnackbar(validationFailed)
                                return@action
                            }
                        }
                        val id = graph.repository.addAccount(provider.id, alias, secret, team)
                        selectedId = id
                        route = "detail"
                        if (provider.authMode == AuthMode.WEB_PROFILE) {
                            context.startActivity(Intent(context, ProviderWebActivity::class.java).putExtra("accountId", id).putExtra("newAccount", true))
                        } else if (test || provider.authMode == AuthMode.NONE) graph.repository.refresh(id)
                    } })
                    "providers" -> ProviderScreen(graph.registry.definitions)
                    "diagnostics" -> DiagnosticsScreen(graph)
                    "detail" -> {
                        val selected = accounts.find { it.account.id == selectedId }
                        if (selected != null) DetailScreen(selected, graph, settings, onRefresh = { refresh(selected.account) }, onChange = { updated -> action { graph.repository.updateAccount(updated) } },
                            onDelete = { action { graph.repository.deleteAccount(selected.account.id); route = "home" } },
                            onLogout = { action { graph.repository.logout(selected.account.id) } },
                            onCredential = { secret -> action { graph.repository.setCredential(selected.account.id, secret); graph.repository.refresh(selected.account.id) } })
                        else Text(stringResource(R.string.no_accounts), Modifier.padding(24.dp))
                    }
                    else -> when (tab) {
                        0, 1 -> AccountList(accounts, graph, settings, tab == 0, onAdd = { route = "add" }, onProviders = { route = "providers" },
                            onOpen = { selectedId = it; route = "detail" }, onRefresh = ::refresh,
                            onRefreshAll = { action { graph.repository.refreshAll() } })
                        2 -> WidgetsScreen(accounts)
                        3 -> SettingsScreen(settings, accounts, onChange = { action { graph.settings.update(it) } },
                            onDiagnostics = { route = "diagnostics" }, onProviders = { route = "providers" }, onClear = { kind -> action {
                                when (kind) { 0 -> graph.repository.clearCredentials(apiOnly = true); 1 -> graph.repository.clearCredentials(webOnly = true); else -> graph.repository.clearData() }
                                snackbar.showSnackbar(done)
                            } })
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountList(accounts: List<AccountOverview>, graph: AppGraph, settings: AppSettings, dashboard: Boolean,
    onAdd: () -> Unit, onProviders: () -> Unit, onOpen: (String) -> Unit, onRefresh: (Account) -> Unit, onRefreshAll: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (accounts.isEmpty()) item {
            Column(Modifier.padding(vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.no_accounts), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.empty_description))
                Button(onClick = onAdd) { Text(stringResource(R.string.add_account)) }
                TextButton(onClick = onProviders) { Text(stringResource(R.string.provider_status)) }
            }
        } else {
            if (dashboard) item {
                TextButton(onClick = onRefreshAll) { Text(stringResource(R.string.refresh_all)) }
                Text(stringResource(R.string.last_sync, accounts.mapNotNull { it.account.lastSuccessAt }.maxOrNull()?.let(::timeLabel) ?: stringResource(R.string.never)), style = MaterialTheme.typography.bodySmall)
            }
            items(accounts, key = { it.account.id }) { overview ->
                val provider = graph.registry.definition(overview.account.providerId)
                if (dashboard) AccountCard(overview.account, overview.snapshot, provider?.displayName ?: overview.account.providerId,
                    settings.remaining, settings.staleHours, { onOpen(overview.account.id) }, { onRefresh(overview.account) })
                else OutlinedCard(onClick = { onOpen(overview.account.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(overview.account.alias, style = MaterialTheme.typography.titleMedium)
                        Text(listOfNotNull(provider?.displayName, overview.snapshot?.planName).joinToString(" · "))
                        Text(stringResource(if (overview.account.authMode == AuthMode.WEB_PROFILE) R.string.foreground else R.string.background), style = MaterialTheme.typography.bodySmall)
                        Text(statusLabel(overview.account, overview.snapshot, settings.staleHours), style = MaterialTheme.typography.labelMedium)
                        Text(stringResource(R.string.last_sync, overview.account.lastSuccessAt?.let(::timeLabel) ?: stringResource(R.string.never)), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddAccountScreen(graph: AppGraph, busy: Boolean, onAdd: (ProviderDefinition, String, String?, String?, Boolean) -> Unit) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var team by rememberSaveable { mutableStateOf("") }
    val provider = graph.registry.definition(selected.orEmpty())
    val webSupported = remember { ProfileSessions.supported() }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (provider == null) {
            item { Text(stringResource(R.string.selected_provider), style = MaterialTheme.typography.titleLarge) }
            items(graph.registry.definitions, key = { it.id }) { definition ->
                OutlinedCard(onClick = { selected = definition.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(definition.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(connectorLabel(definition.status))
                        Text(stringResource(if (definition.capabilities.supportsBackgroundSync) R.string.background else if (definition.capabilities.supportsForegroundSync) R.string.foreground else R.string.manual), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            item {
                TextButton(onClick = { selected = null; secret = "" }) { Text(stringResource(R.string.selected_provider)) }
                Text(provider.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(connectorLabel(provider.status), Modifier.padding(vertical = 8.dp))
                ProviderExplanation(provider)
            }
            if (provider.supported) {
                item { OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.alias)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                if (provider.authMode == AuthMode.API_KEY) {
                    item { OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.credential)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) }
                    item { OutlinedTextField(team, { team = it }, label = { Text(stringResource(R.string.organization)) }, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    item {
                        Button(onClick = { onAdd(provider, alias, secret.trim(), team.takeIf { it.isNotBlank() }, true) }, enabled = !busy && secret.isNotBlank() && (provider.id != "xai-api" || team.isNotBlank())) { Text(stringResource(R.string.test_save)) }
                        TextButton(onClick = { onAdd(provider, alias, secret.trim(), team.takeIf { it.isNotBlank() }, false) }, enabled = !busy && secret.isNotBlank() && (provider.id != "xai-api" || team.isNotBlank())) { Text(stringResource(R.string.save_unvalidated)) }
                    }
                } else item {
                    if (provider.authMode == AuthMode.WEB_PROFILE && !webSupported) Text(stringResource(R.string.web_unsupported))
                    Button(onClick = { onAdd(provider, alias, null, null, false) }, enabled = !busy && (provider.authMode != AuthMode.WEB_PROFILE || webSupported)) {
                        Text(stringResource(if (provider.authMode == AuthMode.WEB_PROFILE) R.string.open_login else R.string.add_account))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderExplanation(provider: ProviderDefinition) {
    Text(stringResource(when { !provider.supported -> R.string.unsupported; provider.authMode == AuthMode.WEB_PROFILE -> R.string.web_explanation; provider.authMode == AuthMode.API_KEY -> R.string.api_explanation; else -> R.string.demo }))
    if (provider.authMode == AuthMode.API_KEY) Text(stringResource(if (provider.id == "xai-api") R.string.xai_key_help else R.string.admin_key_help), Modifier.padding(top = 8.dp))
    if (provider.supported && provider.authMode != AuthMode.NONE) Text(stringResource(R.string.live_validation_pending), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ProviderScreen(providers: List<ProviderDefinition>) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.local_first)) }
        items(providers, key = { it.id }) { provider -> OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(provider.displayName, style = MaterialTheme.typography.titleLarge)
                Text(connectorLabel(provider.status))
                Text(stringResource(if (provider.capabilities.supportsBackgroundSync) R.string.background else if (provider.capabilities.supportsForegroundSync) R.string.foreground else R.string.manual))
                ProviderExplanation(provider)
            }
        } }
    }
}
