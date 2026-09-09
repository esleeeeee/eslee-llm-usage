package com.eslee.llmusage.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutResolverTest {
    @Test fun boundariesOnBothAxes() {
        val cases = listOf(Triple(110f,72f,WidgetSize.S),Triple(180f,110f,WidgetSize.M),Triple(250f,180f,WidgetSize.L),Triple(320f,250f,WidgetSize.XL))
        cases.forEach { (w,h,size) ->
            assertEquals(size,WidgetLayoutResolver.resolve(w,h))
            assertEquals(WidgetSize.entries[size.ordinal - 1],WidgetLayoutResolver.resolve(w - .1f,h))
            assertEquals(WidgetSize.entries[size.ordinal - 1],WidgetLayoutResolver.resolve(w,h - .1f))
        }
        assertEquals(WidgetSize.XS,WidgetLayoutResolver.resolve(500f,40f))
        assertEquals(WidgetSize.XS,WidgetLayoutResolver.resolve(40f,500f))
    }
}
