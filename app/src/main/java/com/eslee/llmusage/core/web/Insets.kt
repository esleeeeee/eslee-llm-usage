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

/**
 * For a screen whose own background is always white (the provider browser). Edge-to-edge
 * picks bar icons from the system theme, so a phone in dark mode drew white clock and battery
 * icons on that white panel (reported 2026-11-05). Dark icons, whatever the system theme.
 */
fun ComponentActivity.useDarkSystemBarIcons() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = true
        isAppearanceLightNavigationBars = true
    }
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
