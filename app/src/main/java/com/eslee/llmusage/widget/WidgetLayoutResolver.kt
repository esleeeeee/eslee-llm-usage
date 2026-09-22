package com.eslee.llmusage.widget

import kotlin.math.ceil

/** How many rings fit and how large they are, for one widget size in dp. */
data class WidgetGrid(val columns: Int, val rows: Int, val gauge: Float, val showTitle: Boolean, val showCaption: Boolean) {
    val capacity: Int get() = columns * rows
}

object WidgetLayoutResolver {
    const val PADDING = 8f
    const val MIN_SLOT_WIDTH = 64f
    const val MIN_ROW_HEIGHT = 72f
    const val TITLE_HEIGHT = 18f
    const val CAPTION_HEIGHT = 16f
    const val RING_GAP = 6f
    const val MIN_GAUGE = 36f
    const val MAX_GAUGE = 120f

    /**
     * Every split of the rings into columns is tried. The one with the largest
     * rings wins unless a split with more rows keeps them at three quarters of
     * that size, in which case the taller layout is taken: a widget given two
     * cells of height should fill them, not line everything up along the top.
     */
    fun resolve(width: Float, height: Float, slots: Int): WidgetGrid {
        val innerWidth = (width - 2 * PADDING).coerceAtLeast(MIN_SLOT_WIDTH)
        val innerHeight = (height - 2 * PADDING).coerceAtLeast(40f)
        val wanted = slots.coerceAtLeast(1)
        val maxColumns = (innerWidth / MIN_SLOT_WIDTH).toInt().coerceIn(1, wanted)
        val maxRows = (innerHeight / MIN_ROW_HEIGHT).toInt().coerceAtLeast(1)
        val candidates = (1..maxColumns).map { columns ->
            val rows = ceil(wanted / columns.toFloat()).toInt().coerceIn(1, maxRows)
            layout(columns, rows, innerWidth / columns, innerHeight / rows)
        }
        val complete = candidates.filter { it.capacity >= wanted }.ifEmpty { listOf(candidates.maxBy { it.capacity }) }
        val largest = complete.maxOf { it.gauge }
        return complete.filter { it.gauge >= largest * 0.75f }
            .sortedWith(compareByDescending<WidgetGrid> { it.rows }.thenByDescending { it.gauge })
            .first()
    }

    /** Text lines are dropped from the bottom up when the row is short, so the ring itself always survives. */
    private fun layout(columns: Int, rows: Int, slotWidth: Float, rowHeight: Float): WidgetGrid {
        val showTitle = rowHeight >= 72f
        val showCaption = rowHeight >= 118f
        val text = (if (showTitle) TITLE_HEIGHT + RING_GAP else 0f) + (if (showCaption) CAPTION_HEIGHT else 0f)
        val gauge = minOf(slotWidth - 8f, rowHeight - text - 4f).coerceIn(MIN_GAUGE, MAX_GAUGE)
        return WidgetGrid(columns, rows, gauge, showTitle, showCaption)
    }
}
