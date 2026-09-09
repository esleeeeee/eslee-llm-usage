package com.eslee.llmusage.widget

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.eslee.llmusage.core.model.Account
import com.eslee.llmusage.core.model.AuthMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WidgetRenderingTest {
    @Test fun rendersEverySizeWithKnownUnknownAndWarnings() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sizes = listOf(72 to 64,160 to 96,220 to 150,300 to 150,300 to 220,360 to 300,360 to 480)
        val account = Account("fixture", "demo", "An intentionally long account alias for overflow",AuthMode.NONE)
        val rows = listOf(
            WidgetAccountRow(account,"Demo",listOf(WidgetValue("Primary","0%",0f,"Reset in 2 h")),"Stale","08:21",null),
            WidgetAccountRow(account.copy(id="fixture2"),"Demo",listOf(WidgetValue("Primary","100%",1f,"Check reset")),"Sign in required","08:21",null),
            WidgetAccountRow(null,"",emptyList(),"Account deleted",null,null),
            WidgetAccountRow(account.copy(id="fixture3"),"Demo",listOf(WidgetValue("Primary","Unknown",null,"Reset unknown")),"Sync failed",null,null),
        )
        for((width,height) in sizes) {
            val views = renderWidgetPreview(context,WidgetConfig(1),rows,DpSize(width.dp,height.dp))
            instrumentation.runOnMainSync {
                val parent = FrameLayout(context)
                val view = views.apply(context,parent)
                val density = context.resources.displayMetrics.density
                view.measure(View.MeasureSpec.makeMeasureSpec((width*density).toInt(),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec((height*density).toInt(),View.MeasureSpec.EXACTLY))
                view.layout(0,0,view.measuredWidth,view.measuredHeight)
                assertTrue(view.measuredWidth > 0 && view.measuredHeight > 0)
                val labels = labels(view)
                assertTrue("Zero must remain distinct from unknown at $width x $height", labels.any { it.contains("0%") })
                assertTrue("Stale warning must survive compact layouts",labels.any { it.contains("Stale") })
            }
        }
    }
    private fun labels(view: View): List<String> = when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { labels(view.getChildAt(it)) }
        else -> emptyList()
    }
}
