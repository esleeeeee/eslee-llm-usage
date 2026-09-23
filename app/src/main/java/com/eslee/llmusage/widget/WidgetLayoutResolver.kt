package com.eslee.llmusage.widget

import kotlin.math.ceil
import kotlin.math.min

/** How many rings fit and how large they are, for one widget size in dp. */
data class WidgetGrid(val columns: Int, val rows: Int, val slotWidth: Float, val gauge: Float, val showTitle: Boolean, val showCaption: Boolean) {
    val capacity: Int get() = columns * rows
}

object WidgetLayoutResolver {
    const val PADDING = 6f
    /** A launcher cell is about this wide in dp; one ring sits in each cell across. */
    const val CELL_WIDTH = 72f
    const val MIN_ROW_HEIGHT = 70f
    const val TITLE_HEIGHT = 15f
    const val CAPTION_HEIGHT = 13f
    const val RING_GAP = 3f
    const val CAPTION_GAP = 1f
    const val MIN_GAUGE = 36f
    const val MAX_GAUGE = 76f

    /** How many cells wide the widget is, judged from its width alone: a 4×n widget always has four rings across. */
    fun cells(width: Float): Int = (width / CELL_WIDTH).toInt().coerceAtLeast(1)

    /**
     * Columns follow the widget's width in cells, never the number of accounts:
     * a 4×2 widget keeps four rings in a row and only opens a second row when a
     * fifth account needs it; a 2×3 widget stacks two rings three deep. Rows
     * that do not fit the height are dropped. The countdown line stays under
     * the name whenever the ring above it can keep its minimum size.
     */
    fun resolve(width: Float, height: Float, slots: Int, columns: Int = cells(width)): WidgetGrid {
        val innerWidth = (width - 2 * PADDING).coerceAtLeast(MIN_GAUGE + 8f)
        val innerHeight = (height - 2 * PADDING).coerceAtLeast(40f)
        val wanted = slots.coerceAtLeast(1)
        val cols = columns.coerceAtLeast(1)
        val needed = ceil(wanted / cols.toFloat()).toInt().coerceAtLeast(1)
        val fit = (innerHeight / MIN_ROW_HEIGHT).toInt().coerceAtLeast(1)
        val rows = min(needed, fit)
        val slotWidth = innerWidth / cols
        val rowHeight = innerHeight / rows
        val showCaption = ring(slotWidth, rowHeight, title = true, caption = true) >= MIN_GAUGE
        val showTitle = showCaption || ring(slotWidth, rowHeight, title = true, caption = false) >= MIN_GAUGE
        val gauge = ring(slotWidth, rowHeight, showTitle, showCaption).coerceIn(MIN_GAUGE, MAX_GAUGE)
        return WidgetGrid(cols, rows, slotWidth, gauge, showTitle, showCaption)
    }

    private fun ring(slotWidth: Float, rowHeight: Float, title: Boolean, caption: Boolean): Float {
        val text = (if (title) TITLE_HEIGHT + RING_GAP else 0f) + (if (caption) CAPTION_HEIGHT + CAPTION_GAP else 0f)
        return min(slotWidth - 8f, rowHeight - text - 2f)
    }
}
