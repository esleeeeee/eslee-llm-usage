package com.eslee.llmusage.widget

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    @Test fun rendersEverySizeWithKnownUnknownAndWarnings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sizes = listOf(72 to 56, 72 to 96, 160 to 100, 220 to 150, 320 to 100, 320 to 200, 360 to 480)
        val slots = listOf(
            WidgetSlot("fixture", "chatgpt", "An intentionally long account alias for overflow", 0.0, "0", "Stale", warning = true),
            WidgetSlot("fixture2", "claude", "Pro", 100.0, "100", "↻ 2h", warning = false),
            WidgetSlot(null, null, "Account deleted", null, "—", "Configure", warning = true),
            WidgetSlot("fixture3", "grok", "Grok", null, "—", "Sync failed", warning = true),
        )
        for ((width, height) in sizes) {
            val views = renderWidgetPreview(context, WidgetConfig(1), slots, DpSize(width.dp, height.dp))
            instrumentation.runOnMainSync {
                val parent = FrameLayout(context)
                val view = views.apply(context, parent)
                val density = context.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((width * density).toInt(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec((height * density).toInt(), View.MeasureSpec.EXACTLY))
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                assertTrue(view.measuredWidth > 0 && view.measuredHeight > 0)
                val labels = labels(view)
                val grid = WidgetLayoutResolver.resolve(width.toFloat(), height.toFloat(), slots.size)
                assertTrue("Zero must remain distinct from unknown at $width x $height", labels.any { it == "0" })
                // A one-ring widget shows only the first slot; the dash belongs to the third and fourth.
                if (grid.capacity >= 3) assertTrue("Unknown must be a dash at $width x $height", labels.any { it == "—" })
                if (grid.showCaption) assertTrue("Stale warning must survive layouts with a caption line", labels.any { it.contains("Stale") })
            }
        }
    }
    private fun labels(view: View): List<String> = when (view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
