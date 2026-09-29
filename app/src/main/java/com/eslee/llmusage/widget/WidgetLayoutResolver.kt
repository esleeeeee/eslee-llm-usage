package com.eslee.llmusage.widget

import kotlin.math.ceil
import kotlin.math.min

/**
 * How many rings fit and how large they are, for one widget size in dp.
 * [showDetail] adds a second line under the name: the countdown spelled out and
 * the moment of the reset. [side] is kept free on both sides of the rings when
 * [showRefresh] puts the refresh button in the top right corner, so the rings
 * stay centred on the widget. [ringGap] and [lineGap] open up when the row has
 * height to spare.
 */
data class WidgetGrid(
    val columns: Int,
    val rows: Int,
    val slotWidth: Float,
    val gauge: Float,
    val showTitle: Boolean,
    val showCaption: Boolean,
    val showDetail: Boolean = false,
    val showRefresh: Boolean = false,
    val side: Float = 0f,
    val ringGap: Float = WidgetLayoutResolver.RING_GAP,
    val lineGap: Float = WidgetLayoutResolver.CAPTION_GAP,
) {
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
    /** The gaps a row with height to spare uses instead. */
    const val ROOMY_RING_GAP = 6f
    const val ROOMY_CAPTION_GAP = 2f
    const val MIN_GAUGE = 36f
    const val MAX_GAUGE = 76f
    /** The detail line is only worth having while the ring above it stays this large. */
    const val DETAIL_MIN_GAUGE = 44f
    /** Narrower widgets give their whole width to rings; their rings open the app instead. */
    const val REFRESH_MIN_WIDTH = 200f
    /**
     * Kept free on each side when the refresh button shows. The button sits in the
     * right one, at the top; the left one is what keeps the rings centred. A column
     * reserved on the right alone pushed every ring left of the widget's middle.
     */
    const val REFRESH_SIDE = 24f
    /** The refresh button's touch target in the top right corner; its icon is centred in it. */
    const val REFRESH_TARGET = 40f

    /** How many cells wide the widget is, judged from its width alone: a 4×n widget always has four rings across. */
    fun cells(width: Float): Int = (width / CELL_WIDTH).toInt().coerceAtLeast(1)

    /** The grid for a whole widget: the refresh button's room first, then the rings in what is left. */
    fun forWidget(width: Float, height: Float, slots: Int): WidgetGrid {
        val refresh = width >= REFRESH_MIN_WIDTH
        val side = if (refresh) REFRESH_SIDE else 0f
        return resolve(width - 2 * side, height, slots, columns = cells(width)).copy(showRefresh = refresh, side = side)
    }

    /**
     * Columns follow the widget's width in cells, never the number of accounts:
     * a 4×2 widget keeps four rings in a row and only opens a second row when a
     * fifth account needs it; a 2×3 widget stacks two rings three deep. Rows
     * that do not fit the height are dropped. The countdown line stays under
     * the name whenever the ring above it can keep its minimum size, and a
     * second line follows it when the row has room to spare.
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
        val showCaption = ring(slotWidth, rowHeight, title = true, captionLines = 1) >= MIN_GAUGE
        val showTitle = showCaption || ring(slotWidth, rowHeight, title = true, captionLines = 0) >= MIN_GAUGE
        // A second line costs ring size only where the height is short; a tall row pays nothing for it.
        val showDetail = showCaption && ring(slotWidth, rowHeight, title = true, captionLines = 2) >= min(DETAIL_MIN_GAUGE, slotWidth - 8f)
        val lines = when { showDetail -> 2; showCaption -> 1; else -> 0 }
        val gauge = ring(slotWidth, rowHeight, showTitle, lines).coerceIn(MIN_GAUGE, MAX_GAUGE)
        // Lines set 1dp apart read as one block; where the row is taller than its
        // content needs, the name and the countdown get room to breathe.
        val roomy = showTitle && rowHeight - gauge - text(showTitle, lines, RING_GAP, CAPTION_GAP) >=
            (ROOMY_RING_GAP - RING_GAP) + lines * (ROOMY_CAPTION_GAP - CAPTION_GAP) + 8f
        return WidgetGrid(cols, rows, slotWidth, gauge, showTitle, showCaption, showDetail,
            ringGap = if (roomy) ROOMY_RING_GAP else RING_GAP, lineGap = if (roomy) ROOMY_CAPTION_GAP else CAPTION_GAP)
    }

    private fun text(title: Boolean, captionLines: Int, ringGap: Float, lineGap: Float): Float =
        (if (title) TITLE_HEIGHT + ringGap else 0f) + captionLines * (CAPTION_HEIGHT + lineGap)

    private fun ring(slotWidth: Float, rowHeight: Float, title: Boolean, captionLines: Int): Float =
        min(slotWidth - 8f, rowHeight - text(title, captionLines, RING_GAP, CAPTION_GAP) - 2f)
}
