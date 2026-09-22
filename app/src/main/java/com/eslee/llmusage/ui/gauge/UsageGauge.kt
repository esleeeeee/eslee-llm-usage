package com.eslee.llmusage.ui.gauge

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The battery ring the app draws; the widget draws the same geometry into a bitmap. */
@Composable
fun UsageGauge(
    remainingPercent: Double?,
    mark: Painter?,
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    number: String = GaugeGeometry.numberText(remainingPercent),
    warning: Boolean = false,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val level = GaugeGeometry.level(remainingPercent)
    val fill = Color(if (warning && level != GaugeGeometry.Level.UNKNOWN) GaugeGeometry.warningArgb(dark) else GaugeGeometry.fillArgb(level, dark))
    val track = Color(GaugeGeometry.trackArgb(dark))
    val sweep = GaugeGeometry.sweep(remainingPercent)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(modifier.size(size), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.fillMaxSize()) {
            val diameter = this.size.width
            val stroke = diameter * GaugeGeometry.STROKE_FRACTION
            val ring = diameter * GaugeGeometry.RING_FRACTION - stroke
            val topLeft = Offset((diameter - ring) / 2f, diameter * GaugeGeometry.RING_CENTER_Y - ring / 2f)
            val style = Stroke(width = stroke, cap = StrokeCap.Round)
            drawArc(track, GaugeGeometry.START_ANGLE, GaugeGeometry.SWEEP_ANGLE, false, topLeft, Size(ring, ring), style = style)
            if (sweep > 0f) drawArc(fill, GaugeGeometry.START_ANGLE, sweep, false, topLeft, Size(ring, ring), style = style)
        }
        if (mark != null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(mark, contentDescription = null, modifier = Modifier.size(size * GaugeGeometry.MARK_FRACTION), tint = onSurface)
        }
        Text(
            number,
            color = onSurface,
            fontSize = (size.value * GaugeGeometry.NUMBER_FRACTION).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            softWrap = false,
        )
    }
}
