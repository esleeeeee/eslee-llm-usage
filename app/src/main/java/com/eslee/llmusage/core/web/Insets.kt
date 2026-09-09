package com.eslee.llmusage.core.web

import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

fun ComponentActivity.enableEdgeToEdgeContent() {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    enableEdgeToEdge()
}

fun View.applySafeDrawingInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.statusBars() or
                WindowInsetsCompat.Type.navigationBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime(),
        )
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
