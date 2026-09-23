package com.eslee.llmusage.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutResolverTest {
    /** The ring area is the widget minus the 48dp refresh column on the right. */
    private fun fourByN(height: Float, slots: Int) = WidgetLayoutResolver.resolve(300f - 48f, height, slots, columns = WidgetLayoutResolver.cells(300f))

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
        val grid = WidgetLayoutResolver.resolve(150f, 300f, slots = 6, columns = WidgetLayoutResolver.cells(150f))
        assertEquals(2, grid.columns)
        assertEquals(3, grid.rows)
        assertTrue(grid.showTitle)
        assertTrue(grid.showCaption)
    }

    @Test fun fewerAccountsThanCellsKeepCellSizedSlots() {
        val two = fourByN(100f, slots = 2)
        val four = fourByN(100f, slots = 4)
        assertEquals(four.slotWidth, two.slotWidth, 0.01f)
        assertEquals(four.gauge, two.gauge, 0.01f)
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

    @Test fun ringsNeverExceedTheirSlotOrTheirRowOrTheCap() {
        listOf(72f to 96f, 150f to 200f, 225f to 100f, 300f to 100f, 300f to 200f, 300f to 300f, 375f to 480f).forEach { (width, height) ->
            (1..8).forEach { slots ->
                val grid = WidgetLayoutResolver.resolve(width, height, slots, columns = WidgetLayoutResolver.cells(width))
                val row = (height - 2 * WidgetLayoutResolver.PADDING) / grid.rows
                assertTrue("$width x $height / $slots", grid.gauge <= grid.slotWidth || grid.gauge == WidgetLayoutResolver.MIN_GAUGE)
                assertTrue("$width x $height / $slots", grid.gauge <= row || grid.gauge == WidgetLayoutResolver.MIN_GAUGE)
                assertTrue("$width x $height / $slots", grid.gauge <= WidgetLayoutResolver.MAX_GAUGE)
                assertTrue("$width x $height / $slots", grid.capacity >= minOf(slots, grid.columns))
            }
        }
    }
}
