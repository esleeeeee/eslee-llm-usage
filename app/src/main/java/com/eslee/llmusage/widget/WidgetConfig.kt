package com.eslee.llmusage.widget

import android.content.Context
import com.eslee.llmusage.app.UsageApplication
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class WidgetTheme { SYSTEM, LIGHT, DARK }
@Serializable enum class WidgetBackground { TRANSLUCENT, SOLID, NONE }
@Serializable data class WidgetSelection(val accountId: String, val bucketSelector: String = "PRIMARY")

/**
 * What a widget shows: which accounts, one ring per selection or one per
 * quota, and how the panel behind them is painted. Earlier versions stored
 * layout styles, used/remaining choices and tap actions here; the ring has one
 * layout and always reads what is left, so those keys are ignored on decode.
 */
@Serializable data class WidgetConfig(
    val appWidgetId: Int,
    val selections: List<WidgetSelection> = emptyList(),
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val background: WidgetBackground = WidgetBackground.TRANSLUCENT,
)

/** Contains presentation preferences and account IDs only; never credentials or page text. */
class WidgetConfigStore(context: Context) {
    private val repository = (context.applicationContext as UsageApplication).graph.repository
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun get(id: Int): WidgetConfig = repository.widgetJson(id)?.let {
        runCatching { json.decodeFromString<WidgetConfig>(it) }.getOrNull()
    } ?: WidgetConfig(id)
    suspend fun all(): List<WidgetConfig> = repository.widgetConfigs().map { get(it.first) }
    suspend fun save(config: WidgetConfig) { repository.saveWidget(config.appWidgetId, json.encodeToString(config), config.selections.map { it.accountId }) }
    suspend fun delete(id: Int) { repository.deleteWidget(id) }
    suspend fun clear() { all().forEach { delete(it.appWidgetId) } }
    suspend fun prune(activeIds: Set<Int>) { all().filter { it.appWidgetId !in activeIds }.forEach { delete(it.appWidgetId) } }
}
