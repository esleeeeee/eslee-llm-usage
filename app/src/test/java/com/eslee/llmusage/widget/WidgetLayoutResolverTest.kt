package com.eslee.llmusage.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutResolverTest {
    @Test fun aFourCellRowHoldsFourRingsWithNameAndCountdown() {
        val grid = WidgetLayoutResolver.resolve(320f, 100f, slots = 4)
        assertEquals(4, grid.columns)
        assertEquals(1, grid.rows)
        assertTrue(grid.showTitle)
        assertTrue(grid.showCaption)
        assertTrue("ring ${grid.gauge}", grid.gauge in 48f..70f)
    }

    @Test fun fewerAccountsThanColumnsDoNotLeaveEmptyColumns() {
        val grid = WidgetLayoutResolver.resolve(320f, 100f, slots = 2)
        assertEquals(2, grid.columns)
        assertEquals(2, grid.capacity)
    }

    @Test fun aTallWidgetAddsASecondRowBeforeShrinkingRings() {
        val grid = WidgetLayoutResolver.resolve(320f, 200f, slots = 6)
        assertEquals(4, grid.columns)
        assertEquals(2, grid.rows)
        assertEquals(8, grid.capacity)
        val single = WidgetLayoutResolver.resolve(320f, 200f, slots = 3)
        assertEquals(1, single.rows)
        assertTrue("rings grow into the height: ${single.gauge}", single.gauge > grid.gauge)
    }

    @Test fun aShortWidgetDropsTextFromTheBottomButKeepsTheRing() {
        val noCaption = WidgetLayoutResolver.resolve(160f, 80f, slots = 2)
        assertTrue(noCaption.showTitle)
        assertFalse(noCaption.showCaption)
        val ringOnly = WidgetLayoutResolver.resolve(72f, 56f, slots = 1)
        assertFalse(ringOnly.showTitle)
        assertFalse(ringOnly.showCaption)
        assertTrue(ringOnly.gauge >= 36f)
    }

    @Test fun aSingleCellStillFitsOneRing() {
        val grid = WidgetLayoutResolver.resolve(72f, 96f, slots = 1)
        assertEquals(1, grid.columns)
        assertEquals(1, grid.capacity)
        assertTrue(grid.gauge >= 36f)
    }

    @Test fun ringsNeverExceedTheirColumnOrTheirRow() {
        listOf(72f to 96f, 160f to 100f, 320f to 100f, 320f to 200f, 360f to 480f).forEach { (width, height) ->
            (1..8).forEach { slots ->
                val grid = WidgetLayoutResolver.resolve(width, height, slots)
                val column = (width - 2 * WidgetLayoutResolver.PADDING) / grid.columns
                val row = (height - 2 * WidgetLayoutResolver.PADDING) / grid.rows
                assertTrue("$width x $height / $slots", grid.gauge <= column || grid.gauge == 36f)
                assertTrue("$width x $height / $slots", grid.gauge <= row || grid.gauge == 36f)
            }
        }
    }
}
