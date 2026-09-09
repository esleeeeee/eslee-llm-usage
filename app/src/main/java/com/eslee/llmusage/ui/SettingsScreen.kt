package com.eslee.llmusage.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.AuthMode
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.usage.AccountOverview

@Composable
internal fun SettingsScreen(settings: AppSettings, accounts: List<AccountOverview>, onChange: (AppSettings) -> Unit,
    onDiagnostics: () -> Unit, onProviders: () -> Unit, onClear: (Int) -> Unit) {
    var clear by remember { mutableStateOf<Int?>(null) }
    clear?.let { kind -> ConfirmDelete(listOf(R.string.delete_credentials, R.string.delete_sessions, R.string.delete_all)[kind], R.string.confirm_clear,
        onDismiss = { clear = null }) { clear = null; onClear(kind) } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionTitle(R.string.sync_settings)
            ChoiceSetting(stringResource(R.string.interval), settings.intervalMinutes, listOf(0L, 15L, 30L, 60L, 180L, 360L),
                label = { if (it == 0L) stringResource(R.string.manual) else stringResource(R.string.minutes, it.toInt()) }) { onChange(settings.copy(intervalMinutes = it)) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.wifi_only), Modifier.weight(1f))
                Switch(settings.wifiOnly, { onChange(settings.copy(wifiOnly = it)) })
            }
            ChoiceSetting(stringResource(R.string.stale_threshold), settings.staleHours, listOf(0, 1, 2, 6, 12, 24),
                label = { if (it == 0) stringResource(R.string.provider_default) else stringResource(R.string.hours, it) }) { onChange(settings.copy(staleHours = it)) }
            ChoiceSetting(stringResource(R.string.retention), settings.retentionDays, listOf(7, 30, 90, 0),
                label = { if (it == 0) stringResource(R.string.unlimited) else stringResource(R.string.days, it) }) { onChange(settings.copy(retentionDays = it)) }
        }
        item {
            SectionTitle(R.string.appearance)
            ChoiceSetting(stringResource(R.string.theme), settings.theme, listOf("SYSTEM", "LIGHT", "DARK"), label = { themeLabel(it) }) { onChange(settings.copy(theme = it)) }
            ChoiceSetting(stringResource(R.string.default_widget_theme), settings.widgetTheme, listOf("SYSTEM", "LIGHT", "DARK"), label = { themeLabel(it) }) { onChange(settings.copy(widgetTheme = it)) }
            ChoiceSetting(stringResource(R.string.default_display), settings.remaining, listOf(true, false),
                label = { stringResource(if (it) R.string.remaining else R.string.used) }) { onChange(settings.copy(remaining = it)) }
        }
        item {
            SectionTitle(R.string.security)
            Text(stringResource(R.string.api_count, accounts.count { it.account.authMode == AuthMode.API_KEY }))
            Text(stringResource(R.string.web_count, accounts.count { it.account.profileName != null }))
            listOf(R.string.delete_credentials, R.string.delete_sessions, R.string.delete_all).forEachIndexed { index, resource ->
                TextButton(onClick = { clear = index }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(stringResource(resource)) }
            }
        }
        item {
            HorizontalDivider()
            TextButton(onClick = onDiagnostics) { Text(stringResource(R.string.diagnostics)) }
            TextButton(onClick = onProviders) { Text(stringResource(R.string.about)) }
            Text(stringResource(R.string.local_first), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 12.dp))
        }
    }
}

@Composable
private fun themeLabel(value: String): String = stringResource(when (value) { "DARK" -> R.string.dark; "LIGHT" -> R.string.light; else -> R.string.system })

@Composable
private fun <T> ChoiceSetting(title: String, selected: T, values: List<T>, label: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Box {
            TextButton(onClick = { expanded = true }) { Text(label(selected)) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                values.forEach { value -> DropdownMenuItem(text = { Text(label(value)) }, onClick = { expanded = false; onSelect(value) }) }
            }
        }
    }
}

