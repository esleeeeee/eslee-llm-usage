package com.eslee.llmusage.ui

import android.app.Activity
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// Neutral surfaces so the battery colours of the rings are the only loud thing on screen.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E7A4E), onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F0DF), onPrimaryContainer = Color(0xFF0A3A24),
    secondary = Color(0xFF4E5A66), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E7ED), onSecondaryContainer = Color(0xFF1A2129),
    tertiary = Color(0xFF8A5A00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE4B5), onTertiaryContainer = Color(0xFF3F2800),
    error = Color(0xFFC93A2E), onError = Color.White,
    errorContainer = Color(0xFFFFDAD5), onErrorContainer = Color(0xFF5A0F08),
    background = Color(0xFFF3F4F6), onBackground = Color(0xFF15181D),
    surface = Color(0xFFF3F4F6), onSurface = Color(0xFF15181D),
    surfaceVariant = Color(0xFFE6E9EE), onSurfaceVariant = Color(0xFF5A616D),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFAFBFC),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEDEFF3), surfaceContainerHighest = Color(0xFFE4E7EC),
    outline = Color(0xFFBFC5CE), outlineVariant = Color(0xFFDDE1E7),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF6BD69C), onPrimary = Color(0xFF003920),
    primaryContainer = Color(0xFF1E4D36), onPrimaryContainer = Color(0xFFC6F2D9),
    secondary = Color(0xFFB4BDC8), onSecondary = Color(0xFF212930),
    secondaryContainer = Color(0xFF2B323B), onSecondaryContainer = Color(0xFFDDE3EA),
    tertiary = Color(0xFFFFC96B), onTertiary = Color(0xFF3F2800),
    tertiaryContainer = Color(0xFF5B4000), onTertiaryContainer = Color(0xFFFFE4B5),
    error = Color(0xFFFF7B70), onError = Color(0xFF5A0F08),
    errorContainer = Color(0xFF7A2118), onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF0E1013), onBackground = Color(0xFFE8EBF0),
    surface = Color(0xFF0E1013), onSurface = Color(0xFFE8EBF0),
    surfaceVariant = Color(0xFF23272E), onSurfaceVariant = Color(0xFFA3AAB5),
    surfaceContainerLowest = Color(0xFF0A0C0E), surfaceContainerLow = Color(0xFF131619),
    surfaceContainer = Color(0xFF181C21), surfaceContainerHigh = Color(0xFF21262C), surfaceContainerHighest = Color(0xFF2A3037),
    outline = Color(0xFF4B525C), outlineVariant = Color(0xFF2E343C),
)

private val UsageShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun UsageTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        var context = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        (context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, shapes = UsageShapes, content = content)
}
