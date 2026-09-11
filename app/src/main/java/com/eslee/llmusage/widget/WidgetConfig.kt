package com.eslee.llmusage.widget

import android.content.Context
import com.eslee.llmusage.app.UsageApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class WidgetStyle { AUTO, NUMBER, BAR, LIST, DASHBOARD, RING_OPTIONAL }
@Serializable enum class WidgetTheme { SYSTEM, LIGHT, DARK }
@Serializable enum class WidgetTap { AUTO, OPEN_APP, OPEN_ACCOUNT, REFRESH, OPEN_PROVIDER }
@Serializable data class WidgetSelection(val accountId: String, val bucketSelector: String = "PRIMARY")
@Serializable data class WidgetConfig(
    val appWidgetId: Int,
    val selections: List<WidgetSelection> = emptyList(),
    val style: WidgetStyle = WidgetStyle.AUTO,
    /** null follows the app-wide Used/Remaining setting; false/true pin this widget. */
    val remaining: Boolean? = null,
    val showProvider: Boolean = true,
    val showAlias: Boolean = true,
    val showReset: Boolean = true,
    val showLastSync: Boolean = true,
    val showStatus: Boolean = true,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val tap: WidgetTap = WidgetTap.AUTO,
)

/** Contains presentation preferences and account IDs only; never credentials or page text. */
class WidgetConfigStore(context: Context) {
    private val repository = (context.applicationContext as UsageApplication).graph.repository
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun get(id: Int): WidgetConfig = repository.widgetJson(id)?.let {
        runCatching { json.decodeFromString<WidgetConfig>(it) }.getOrNull()
    } ?: WidgetConfig(id)
    suspend fun all(): List<WidgetConfig> = repository.widgetConfigs().map { get(it.first) }
    suspend fun save(config: WidgetConfig) { repository.saveWidget(config.appWidgetId,json.encodeToString(config),config.selections.map { it.accountId }) }
    suspend fun delete(id: Int) { repository.deleteWidget(id) }
    suspend fun clear() { all().forEach { delete(it.appWidgetId) } }
    suspend fun prune(activeIds: Set<Int>) { all().filter { it.appWidgetId !in activeIds }.forEach { delete(it.appWidgetId) } }
}
