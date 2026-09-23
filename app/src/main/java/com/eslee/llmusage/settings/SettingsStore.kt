package com.eslee.llmusage.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val Context.settingsDataStore by preferencesDataStore("settings")
@Serializable data class AppSettings(
    val intervalMinutes: Long = 15,
    val wifiOnly: Boolean = false,
    val staleHours: Int = 0,
    val retentionDays: Int = 30,
    val theme: String = "SYSTEM",
    val remaining: Boolean = true,
    val widgetTheme: String = "SYSTEM",
    /** Whether opening the app may ask GitHub Releases for a newer build. */
    val updatePrompts: Boolean = true,
    val updateSkipped: String = "",
    val updateCheckedAt: Long = 0,
)
class SettingsStore(context: Context) {
    private val dataStore = context.settingsDataStore
    private val key = stringPreferencesKey("app_settings")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun decode(preferences: Preferences): AppSettings =
        preferences[key]?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() } ?: AppSettings()
    val flow = dataStore.data.map(::decode)
    suspend fun current() = flow.first()
    suspend fun update(value: AppSettings) {
        validate(value)
        dataStore.edit { it[key] = json.encodeToString(value) }
    }
    /**
     * Read, change and write inside one DataStore transaction. A background
     * writer that copies a value it read a moment ago would otherwise put back
     * whatever the user changed in between, such as a theme picked while a
     * release check was finishing.
     */
    suspend fun edit(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            val next = transform(decode(preferences))
            validate(next)
            preferences[key] = json.encodeToString(next)
        }
    }
    private fun validate(value: AppSettings) {
        require(value.intervalMinutes in setOf(0L, 15L, 30L, 60L, 180L, 360L))
        require(value.staleHours in setOf(0, 1, 2, 6, 12, 24))
        require(value.retentionDays in setOf(0, 7, 30, 90))
        require(value.theme in setOf("SYSTEM", "LIGHT", "DARK"))
    }
    suspend fun reset() { dataStore.edit { it.clear() } }
}
