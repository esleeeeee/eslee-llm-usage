package com.eslee.llmusage.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.eslee.llmusage.ui.gauge.GaugeGeometry

/**
 * Glance cannot draw an arc, so the ring is rendered into a small bitmap. Only
 * the ring lives here; the mark and the number are real widget views on top of
 * it, so they scale with the user's font size and stay readable to a screen
 * reader.
 */
object GaugeBitmap {
    fun render(diameterPx: Int, remainingPercent: Double?, dark: Boolean, warning: Boolean = false): Bitmap {
        val size = diameterPx.coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = size * GaugeGeometry.STROKE_FRACTION
        val ring = size * GaugeGeometry.RING_FRACTION - stroke
        val left = (size - ring) / 2f
        val top = size * GaugeGeometry.RING_CENTER_Y - ring / 2f
        val bounds = RectF(left, top, left + ring, top + ring)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = stroke
        }
        paint.color = GaugeGeometry.trackArgb(dark)
        canvas.drawArc(bounds, GaugeGeometry.START_ANGLE, GaugeGeometry.SWEEP_ANGLE, false, paint)
        val level = GaugeGeometry.level(remainingPercent)
        val sweep = GaugeGeometry.sweep(remainingPercent)
        if (level != GaugeGeometry.Level.UNKNOWN && sweep > 0f) {
            paint.color = if (warning) GaugeGeometry.warningArgb(dark) else GaugeGeometry.fillArgb(level, dark)
            canvas.drawArc(bounds, GaugeGeometry.START_ANGLE, sweep, false, paint)
        }
        return bitmap
    }
}
