package com.eslee.llmusage.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3D6755), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3ECDD), onPrimaryContainer = Color(0xFF16382A),
    secondary = Color(0xFF58645C), background = Color(0xFFF8FAF6),
    surface = Color(0xFFF8FAF6), surfaceVariant = Color(0xFFE0E8DF),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFABD3BD), onPrimary = Color(0xFF103729),
    primaryContainer = Color(0xFF284F3E), onPrimaryContainer = Color(0xFFD3ECDD),
    secondary = Color(0xFFBECBBF), background = Color(0xFF101511),
    surface = Color(0xFF101511), surfaceVariant = Color(0xFF3D4840),
)

@Composable
fun UsageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
