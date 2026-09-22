package com.eslee.llmusage.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetLayoutResolverTest {
    @Test fun aFourByOneRowHoldsRingsWithNamesButNoCountdown() {
        val grid = WidgetLayoutResolver.resolve(320f, 100f, slots = 3)
        assertEquals(3, grid.columns)
        assertEquals(1, grid.rows)
        assertTrue(grid.showTitle)
        // One cell of height is the reference battery widget: ring and name only.
        assertFalse(grid.showCaption)
        assertTrue("ring ${grid.gauge}", grid.gauge in 48f..70f)
    }

    @Test fun aFourByTwoGivesBigRingsWithNameAndCountdown() {
        val grid = WidgetLayoutResolver.resolve(320f, 200f, slots = 3)
        assertEquals(3, grid.columns)
        assertEquals(1, grid.rows)
        assertTrue(grid.showTitle)
        assertTrue(grid.showCaption)
        assertTrue("ring ${grid.gauge}", grid.gauge >= 70f)
    }

    /** Reported: everything lined up along one row while the height below stayed empty. */
    @Test fun fourRingsInTwoCellsOfHeightSpreadIntoTwoRows() {
        val grid = WidgetLayoutResolver.resolve(320f, 200f, slots = 4)
        assertEquals(2, grid.rows)
        assertEquals(2, grid.columns)
        assertEquals(4, grid.capacity)
    }

    @Test fun twoRingsInANarrowTallWidgetStackVertically() {
        val grid = WidgetLayoutResolver.resolve(150f, 200f, slots = 2)
        assertEquals(1, grid.columns)
        assertEquals(2, grid.rows)
        assertTrue(grid.showTitle)
    }

    @Test fun twoRingsInAWideTallWidgetStayLargeSideBySide() {
        val grid = WidgetLayoutResolver.resolve(320f, 200f, slots = 2)
        assertEquals(2, grid.columns)
        assertEquals(1, grid.rows)
        assertEquals(WidgetLayoutResolver.MAX_GAUGE, grid.gauge, 0.01f)
    }

    @Test fun aLayoutThatShowsEveryRingBeatsOneThatDropsSome() {
        val grid = WidgetLayoutResolver.resolve(320f, 100f, slots = 4)
        assertEquals(4, grid.capacity)
    }

    @Test fun aShortWidgetDropsTextFromTheBottomButKeepsTheRing() {
        val ringOnly = WidgetLayoutResolver.resolve(72f, 56f, slots = 1)
        assertFalse(ringOnly.showTitle)
        assertFalse(ringOnly.showCaption)
        assertTrue(ringOnly.gauge >= WidgetLayoutResolver.MIN_GAUGE)
    }

    @Test fun ringsNeverExceedTheirColumnOrTheirRow() {
        listOf(72f to 96f, 150f to 200f, 160f to 100f, 320f to 100f, 320f to 200f, 320f to 300f, 360f to 480f).forEach { (width, height) ->
            (1..8).forEach { slots ->
                val grid = WidgetLayoutResolver.resolve(width, height, slots)
                val column = (width - 2 * WidgetLayoutResolver.PADDING) / grid.columns
                val row = (height - 2 * WidgetLayoutResolver.PADDING) / grid.rows
                assertTrue("$width x $height / $slots", grid.gauge <= column || grid.gauge == WidgetLayoutResolver.MIN_GAUGE)
                assertTrue("$width x $height / $slots", grid.gauge <= row || grid.gauge == WidgetLayoutResolver.MIN_GAUGE)
                assertTrue("$width x $height / $slots", grid.capacity >= minOf(slots, grid.columns))
            }
        }
    }
}
