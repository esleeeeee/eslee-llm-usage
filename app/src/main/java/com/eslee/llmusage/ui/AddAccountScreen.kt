package com.eslee.llmusage.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.eslee.llmusage.R
import com.eslee.llmusage.app.AppGraph
import com.eslee.llmusage.core.model.AuthMode
import com.eslee.llmusage.core.web.ProfileSessions
import com.eslee.llmusage.provider.ProviderDefinition

/** Two steps: pick the service, then name the account and sign in or paste a key. */
@Composable
internal fun AddAccountScreen(
    graph: AppGraph, busy: Boolean, onBack: () -> Unit, onProviders: () -> Unit,
    onAdd: (ProviderDefinition, String, String?, String?, Boolean) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var team by rememberSaveable { mutableStateOf("") }
    val provider = graph.registry.definition(selected.orEmpty())
    val webSupported = remember { ProfileSessions.supported() }
    // The system back key steps back to the service list, like the arrow in the bar.
    BackHandler(provider != null) { selected = null; secret = "" }
    ScreenScaffold(stringResource(R.string.add_account), onBack = { if (provider == null) onBack() else { selected = null; secret = "" } }, busy = busy) { padding ->
        if (provider == null) {
            val groups = graph.registry.definitions.groupBy { it.supported }
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text(stringResource(R.string.selected_provider), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp)) }
                items(groups[true].orEmpty(), key = { it.id }) { definition -> ProviderRow(definition) { selected = definition.id } }
                groups[false]?.let { unsupported ->
                    item { Text(stringResource(R.string.unsupported), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) }
                    items(unsupported, key = { it.id }) { definition -> ProviderRow(definition, enabled = false) {} }
                }
                item { TextButton(onClick = onProviders, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.provider_status)) } }
            }
        } else {
            val keyReady = secret.isNotBlank() && (provider.id != "xai-api" || team.isNotBlank())
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ProviderMark(provider.id, size = 48.dp)
                    Column {
                        Text(provider.displayName, style = MaterialTheme.typography.headlineSmall)
                        Text(connectorLabel(provider.status) + " · " + syncModeLabel(provider), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ProviderExplanation(provider)
                OutlinedTextField(alias, { alias = it }, label = { Text(stringResource(R.string.alias)) }, placeholder = { Text(stringResource(R.string.alias_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (provider.authMode == AuthMode.API_KEY) {
                    OutlinedTextField(secret, { secret = it }, label = { Text(stringResource(R.string.credential)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                    OutlinedTextField(team, { team = it }, label = { Text(stringResource(R.string.organization)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Button(onClick = { onAdd(provider, alias, secret.trim(), team.takeIf { it.isNotBlank() }, true) }, enabled = !busy && keyReady, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.test_save)) }
                    TextButton(onClick = { onAdd(provider, alias, secret.trim(), team.takeIf { it.isNotBlank() }, false) }, enabled = !busy && keyReady, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.save_unvalidated)) }
                } else {
                    if (provider.authMode == AuthMode.WEB_PROFILE && !webSupported) Text(stringResource(R.string.web_unsupported), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { onAdd(provider, alias, null, null, false) }, enabled = !busy && (provider.authMode != AuthMode.WEB_PROFILE || webSupported), modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (provider.authMode == AuthMode.WEB_PROFILE) R.string.open_login else R.string.add_account))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderRow(definition: ProviderDefinition, enabled: Boolean = true, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        ListItem(
            leadingContent = { ProviderMark(definition.id) },
            headlineContent = { Text(definition.displayName) },
            supportingContent = { Text(if (enabled) syncModeLabel(definition) else stringResource(R.string.unsupported)) },
            modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
            colors = ListItemDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                headlineColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
    }
}

@Composable
internal fun syncModeLabel(provider: ProviderDefinition): String = stringResource(when {
    provider.authMode == AuthMode.WEB_PROFILE -> R.string.login_method_web
    provider.authMode == AuthMode.API_KEY -> R.string.login_method_api
    provider.capabilities.supportsBackgroundSync -> R.string.background
    provider.capabilities.supportsForegroundSync -> R.string.foreground
    else -> R.string.manual
})

@Composable
internal fun ProviderExplanation(provider: ProviderDefinition) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(when { !provider.supported -> R.string.unsupported; provider.authMode == AuthMode.WEB_PROFILE -> R.string.web_explanation; provider.authMode == AuthMode.API_KEY -> R.string.api_explanation; else -> R.string.demo }),
            style = MaterialTheme.typography.bodyMedium)
        if (provider.authMode == AuthMode.API_KEY) Text(stringResource(if (provider.id == "xai-api") R.string.xai_key_help else R.string.admin_key_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (provider.supported && provider.authMode == AuthMode.API_KEY) Text(stringResource(R.string.live_validation_pending), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
