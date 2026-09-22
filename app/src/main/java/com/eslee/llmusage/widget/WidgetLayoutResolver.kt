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
    /** Rings stop growing here; a two-cell widget gets air around them, not a bigger ring. */
    const val MAX_GAUGE = 76f
    /** A countdown line is only worth having while the ring above it stays this large. */
    const val CAPTION_MIN_GAUGE = 44f

    private data class Candidate(val grid: WidgetGrid, val raw: Float)

    /**
     * Every split of the rings into columns is tried. The one whose rings could
     * be largest wins unless a split with more rows keeps them at two thirds
     * of that, in which case the taller layout is taken: a widget given two
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
        val complete = candidates.filter { it.grid.capacity >= wanted }.ifEmpty { listOf(candidates.maxBy { it.grid.capacity }) }
        val largest = complete.maxOf { it.raw }
        return complete.filter { it.raw >= largest * 0.65f }
            .sortedWith(compareByDescending<Candidate> { it.grid.rows }.thenByDescending { it.raw })
            .first().grid
    }

    /** Text lines are dropped from the bottom up when the row is short, so the ring itself always survives. */
    private fun layout(columns: Int, rows: Int, slotWidth: Float, rowHeight: Float): Candidate {
        val showTitle = rowHeight >= 64f
        val showCaption = showTitle && ring(slotWidth, rowHeight, title = true, caption = true) >= CAPTION_MIN_GAUGE
        val raw = ring(slotWidth, rowHeight, showTitle, showCaption).coerceAtLeast(MIN_GAUGE)
        return Candidate(WidgetGrid(columns, rows, raw.coerceAtMost(MAX_GAUGE), showTitle, showCaption), raw)
    }

    private fun ring(slotWidth: Float, rowHeight: Float, title: Boolean, caption: Boolean): Float {
        val text = (if (title) TITLE_HEIGHT + RING_GAP else 0f) + (if (caption) CAPTION_HEIGHT + 2f else 0f)
        return minOf(slotWidth - 8f, rowHeight - text - 4f)
    }
}
