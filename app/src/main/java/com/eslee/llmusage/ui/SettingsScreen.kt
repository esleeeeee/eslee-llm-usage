package com.eslee.llmusage.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eslee.llmusage.BuildConfig
import com.eslee.llmusage.R
import com.eslee.llmusage.core.model.AuthMode
import com.eslee.llmusage.settings.AppSettings
import com.eslee.llmusage.usage.AccountOverview

@Composable
internal fun SettingsScreen(
    settings: AppSettings, accounts: List<AccountOverview>, onChange: (AppSettings) -> Unit, onBack: () -> Unit,
    onDiagnostics: () -> Unit, onProviders: () -> Unit, onWidgets: () -> Unit, onClear: (Int) -> Unit,
) {
    var clear by remember { mutableStateOf<Int?>(null) }
    clear?.let { kind -> ConfirmDelete(listOf(R.string.delete_credentials, R.string.delete_sessions, R.string.delete_all)[kind], R.string.confirm_clear,
        onDismiss = { clear = null }) { clear = null; onClear(kind) } }
    ScreenScaffold(stringResource(R.string.settings), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
            SettingsGroup(R.string.sync_settings) {
                ChoiceRow(stringResource(R.string.interval), settings.intervalMinutes, listOf(0L, 15L, 30L, 60L, 180L, 360L),
                    label = { if (it == 0L) stringResource(R.string.manual) else stringResource(R.string.minutes, it.toInt()) }) { onChange(settings.copy(intervalMinutes = it)) }
                SwitchRow(stringResource(R.string.wifi_only), settings.wifiOnly) { onChange(settings.copy(wifiOnly = it)) }
                ChoiceRow(stringResource(R.string.stale_threshold), settings.staleHours, listOf(0, 1, 2, 6, 12, 24),
                    label = { if (it == 0) stringResource(R.string.provider_default) else stringResource(R.string.hours, it) }) { onChange(settings.copy(staleHours = it)) }
                ChoiceRow(stringResource(R.string.retention), settings.retentionDays, listOf(7, 30, 90, 0),
                    label = { if (it == 0) stringResource(R.string.unlimited) else stringResource(R.string.days, it) }, last = true) { onChange(settings.copy(retentionDays = it)) }
            }
            SettingsGroup(R.string.appearance) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.bodyLarge)
                    val themes = listOf("SYSTEM" to R.string.system, "LIGHT" to R.string.light, "DARK" to R.string.dark)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        themes.forEachIndexed { index, (value, label) ->
                            SegmentedButton(selected = settings.theme == value, onClick = { onChange(settings.copy(theme = value)) }, shape = SegmentedButtonDefaults.itemShape(index, themes.size)) { Text(stringResource(label)) }
                        }
                    }
                }
            }
            SettingsGroup(R.string.widgets) {
                LinkRow(stringResource(R.string.widgets), stringResource(R.string.widgets_entry_body), last = true, onClick = onWidgets)
            }
            SettingsGroup(R.string.security) {
                Text(
                    stringResource(R.string.api_count, accounts.count { it.account.authMode == AuthMode.API_KEY }) + " · " +
                        stringResource(R.string.web_count, accounts.count { it.account.profileName != null }),
                    Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                listOf(R.string.delete_credentials, R.string.delete_sessions, R.string.delete_all).forEachIndexed { index, resource ->
                    ListItem(
                        headlineContent = { Text(stringResource(resource), color = MaterialTheme.colorScheme.error) },
                        modifier = Modifier.clickable { clear = index },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    )
                    if (index < 2) HorizontalDivider()
                }
            }
            SettingsGroup(R.string.about) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) },
                    supportingContent = { Text(stringResource(R.string.local_first)) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
                HorizontalDivider()
                LinkRow(stringResource(R.string.diagnostics), stringResource(R.string.diagnostics_body), onClick = onDiagnostics)
                LinkRow(stringResource(R.string.provider_status), null, last = true, onClick = onProviders)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsGroup(title: Int, content: @Composable ColumnScope.() -> Unit) {
    SectionTitle(title)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) { Column(content = content) }
}

@Composable
private fun LinkRow(title: String, body: String?, last: Boolean = false, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = body?.let { { Text(it) } },
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    if (!last) HorizontalDivider()
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Switch(checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    HorizontalDivider()
}

/** A row showing the current choice; tapping it opens the list, which is easier to hit than a dropdown. */
@Composable
private fun <T> ChoiceRow(title: String, selected: T, values: List<T>, label: @Composable (T) -> String, last: Boolean = false, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(label(selected), color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable { open = true },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    )
    if (!last) HorizontalDivider()
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column {
                values.forEach { value ->
                    Row(Modifier.fillMaxWidth().clickable { open = false; onSelect(value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = value == selected, onClick = { open = false; onSelect(value) })
                        Text(label(value), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}
