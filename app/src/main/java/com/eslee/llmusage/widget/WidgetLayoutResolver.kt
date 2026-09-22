package com.eslee.llmusage.widget

/** How many rings fit and how large they are, for one widget size in dp. */
data class WidgetGrid(val columns: Int, val rows: Int, val gauge: Float, val showTitle: Boolean, val showCaption: Boolean) {
    val capacity: Int get() = columns * rows
}

object WidgetLayoutResolver {
    const val PADDING = 6f
    const val MIN_SLOT_WIDTH = 64f
    const val MIN_ROW_HEIGHT = 84f
    const val TITLE_HEIGHT = 16f
    const val CAPTION_HEIGHT = 14f

    /**
     * Rings sit in a row like the phone's battery widget; a taller widget adds a
     * second row before shrinking anything. Text lines are dropped from the
     * bottom up when the height is not there, so the ring itself always survives.
     */
    fun resolve(width: Float, height: Float, slots: Int): WidgetGrid {
        val innerWidth = (width - 2 * PADDING).coerceAtLeast(MIN_SLOT_WIDTH)
        val innerHeight = (height - 2 * PADDING).coerceAtLeast(40f)
        val wanted = slots.coerceAtLeast(1)
        val columns = (innerWidth / MIN_SLOT_WIDTH).toInt().coerceIn(1, wanted)
        val neededRows = (wanted + columns - 1) / columns
        val rows = (innerHeight / MIN_ROW_HEIGHT).toInt().coerceIn(1, neededRows)
        val rowHeight = innerHeight / rows
        val slotWidth = innerWidth / columns
        val showTitle = rowHeight >= 64f
        val showCaption = rowHeight >= 88f
        val text = (if (showTitle) TITLE_HEIGHT else 0f) + (if (showCaption) CAPTION_HEIGHT else 0f)
        val gauge = minOf(slotWidth - 6f, rowHeight - text - 2f).coerceIn(36f, 120f)
        return WidgetGrid(columns, rows, gauge, showTitle, showCaption)
    }
}
