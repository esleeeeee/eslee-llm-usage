package com.eslee.llmusage.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutResolverTest {
    /** A four-cell widget, laid out the way the widget lays itself out. */
    private fun fourByN(height: Float, slots: Int) = WidgetLayoutResolver.forWidget(300f, height, slots)

    @Test fun columnsFollowTheWidgetWidthInCells() {
        assertEquals(1, WidgetLayoutResolver.cells(72f))
        assertEquals(2, WidgetLayoutResolver.cells(150f))
        assertEquals(3, WidgetLayoutResolver.cells(225f))
        assertEquals(4, WidgetLayoutResolver.cells(300f))
        assertEquals(5, WidgetLayoutResolver.cells(375f))
    }

    /** Reported: growing a 4×1 widget to 4×2 turned its row of four into a 2×2 block. */
    @Test fun fourRingsStayFourAcrossWhenTheWidgetGetsTaller() {
        val single = fourByN(100f, slots = 4)
        val double = fourByN(200f, slots = 4)
        assertEquals(4, single.columns)
        assertEquals(1, single.rows)
        assertEquals(4, double.columns)
        assertEquals(1, double.rows)
        // Four across means the cell width, not the height, sizes the ring.
        assertTrue("ring never shrinks with height: ${double.gauge} >= ${single.gauge}", double.gauge >= single.gauge)
    }

    @Test fun aFifthAccountOpensASecondRowOnlyWhenTheHeightAllows() {
        assertEquals(1, fourByN(100f, slots = 5).rows)
        assertEquals(4, fourByN(100f, slots = 5).capacity)
        val tall = fourByN(200f, slots = 5)
        assertEquals(2, tall.rows)
        assertEquals(8, tall.capacity)
    }

    @Test fun aTwoByThreeWidgetStacksTwoRingsThreeDeep() {
        val grid = WidgetLayoutResolver.forWidget(150f, 300f, slots = 6)
        assertEquals(2, grid.columns)
        assertEquals(3, grid.rows)
        assertTrue(grid.showTitle)
        assertTrue(grid.showCaption)
    }

    /** Ring size follows the cell, not the account count; a single row then spreads its rings over the width. */
    @Test fun fewerAccountsThanCellsKeepCellSizedRings() {
        val two = fourByN(100f, slots = 2)
        val four = fourByN(100f, slots = 4)
        assertEquals(four.slotWidth, two.slotWidth, 0.01f)
        assertEquals(four.gauge, two.gauge, 0.01f)
        assertEquals(1, two.rows)
    }

    /**
     * Reported: the rings sat left of the widget's middle. The refresh button's room
     * is taken from both sides alike, so the rings stay centred on the widget.
     */
    @Test fun theRefreshButtonTakesItsRoomFromBothSides() {
        val wide = WidgetLayoutResolver.forWidget(320f, 100f, slots = 3)
        assertTrue(wide.showRefresh)
        assertEquals(WidgetLayoutResolver.REFRESH_SIDE, wide.side, 0f)
        assertEquals((320f - 2 * (WidgetLayoutResolver.PADDING + WidgetLayoutResolver.REFRESH_SIDE)) / 4, wide.slotWidth, 0.01f)
        // The icon, centred in its corner target, stays clear of the ring area.
        val iconLeft = 320f - WidgetLayoutResolver.REFRESH_TARGET / 2 - 9f
        assertTrue(iconLeft >= 320f - WidgetLayoutResolver.PADDING - wide.side)
        val narrow = WidgetLayoutResolver.forWidget(150f, 100f, slots = 2)
        assertFalse(narrow.showRefresh)
        assertEquals(0f, narrow.side, 0f)
    }

    /** Reported: the name and two countdown lines were stacked 1dp apart on a 4×2 widget. */
    @Test fun aTallRowGivesItsLinesRoom() {
        val tall = fourByN(200f, slots = 3)
        assertTrue(tall.showDetail)
        assertEquals(WidgetLayoutResolver.ROOMY_RING_GAP, tall.ringGap, 0f)
        assertEquals(WidgetLayoutResolver.ROOMY_CAPTION_GAP, tall.lineGap, 0f)
        val short = fourByN(85f, slots = 3)
        assertEquals(WidgetLayoutResolver.RING_GAP, short.ringGap, 0f)
        assertEquals(WidgetLayoutResolver.CAPTION_GAP, short.lineGap, 0f)
    }

    /** Reported: the reset countdown must always sit under the name, including in a one-cell row. */
    @Test fun aOneCellRowKeepsNameAndCountdown() {
        listOf(85f, 100f, 110f).forEach { height ->
            val grid = fourByN(height, slots = 4)
            assertTrue("title at $height", grid.showTitle)
            assertTrue("caption at $height", grid.showCaption)
            assertTrue("ring at $height: ${grid.gauge}", grid.gauge >= WidgetLayoutResolver.MIN_GAUGE)
        }
    }

    @Test fun onlyAWidgetTooShortForTwoLinesDropsThemFromTheBottom() {
        val ringOnly = WidgetLayoutResolver.resolve(72f, 56f, slots = 1, columns = 1)
        assertFalse(ringOnly.showCaption)
        assertFalse(ringOnly.showTitle)
        assertTrue(ringOnly.gauge >= WidgetLayoutResolver.MIN_GAUGE)
    }

    @Test fun ringsAndTheirLinesNeverExceedTheirSlotOrTheirRowOrTheCap() {
        listOf(72f to 96f, 150f to 200f, 225f to 100f, 300f to 100f, 300f to 200f, 300f to 300f, 375f to 480f).forEach { (width, height) ->
            (1..8).forEach { slots ->
                val grid = WidgetLayoutResolver.forWidget(width, height, slots)
                val row = (height - 2 * WidgetLayoutResolver.PADDING) / grid.rows
                val lines = when { grid.showDetail -> 2; grid.showCaption -> 1; else -> 0 }
                val stack = grid.gauge + (if (grid.showTitle) WidgetLayoutResolver.TITLE_HEIGHT + grid.ringGap else 0f) +
                    lines * (WidgetLayoutResolver.CAPTION_HEIGHT + grid.lineGap)
                assertTrue("$width x $height / $slots", grid.gauge <= grid.slotWidth || grid.gauge == WidgetLayoutResolver.MIN_GAUGE)
                assertTrue("$width x $height / $slots: $stack > $row", stack <= row || grid.gauge == WidgetLayoutResolver.MIN_GAUGE)
                assertTrue("$width x $height / $slots", grid.gauge <= WidgetLayoutResolver.MAX_GAUGE)
                assertTrue("$width x $height / $slots", grid.capacity >= minOf(slots, grid.columns))
            }
        }
    }

    /** Reported: a 4×2 widget should spell out the countdown and show when the reset happens. */
    @Test fun aTallRowSpellsOutTheCountdownOnASecondLine() {
        val tall = fourByN(200f, slots = 4)
        assertTrue(tall.showCaption)
        assertTrue(tall.showDetail)
        val single = fourByN(100f, slots = 4)
        assertTrue(single.showCaption)
        assertFalse(single.showDetail)
        // The second line costs the tall ring nothing: its size is set by the cell's width.
        assertEquals(single.gauge.coerceAtLeast(tall.gauge), tall.gauge, 0.01f)
    }

    @Test fun theDetailLineNeverShrinksARingBelowItsMinimum() {
        listOf(100f, 150f, 200f, 300f).forEach { height ->
            (1..8).forEach { slots ->
                val grid = fourByN(height, slots)
                if (grid.showDetail) assertTrue("$height / $slots: ${grid.gauge}", grid.gauge >= WidgetLayoutResolver.DETAIL_MIN_GAUGE)
            }
        }
    }
}
