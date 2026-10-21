package com.carassistant.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// 主色取加油枪的深绿。油耗这类数字密集的界面，暖色会把注意力从数字上拉走
val Green900 = Color(0xFF12402C)
val Green700 = Color(0xFF1F6F4A)
val Green500 = Color(0xFF2E8B5F)
val Green100 = Color(0xFFDCEFE3)

val Amber500 = Color(0xFFC77700)
val Amber100 = Color(0xFFFCEFD8)

val Ink900 = Color(0xFF14181C)
val Ink600 = Color(0xFF4A5560)
val Ink400 = Color(0xFF8A939C)
val Ink100 = Color(0xFFF3F5F6)
val Paper = Color(0xFFFBFCFC)

private val LightColors = lightColorScheme(
    primary = Green700,
    onPrimary = Color.White,
    primaryContainer = Green100,
    onPrimaryContainer = Green900,
    secondary = Amber500,
    onSecondary = Color.White,
    secondaryContainer = Amber100,
    onSecondaryContainer = Color(0xFF4A2E00),
    background = Paper,
    onBackground = Ink900,
    surface = Color.White,
    onSurface = Ink900,
    surfaceVariant = Ink100,
    onSurfaceVariant = Ink600,
    outline = Color(0xFFD8DEE2),
    outlineVariant = Color(0xFFE8ECEE),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FCF9B),
    onPrimary = Color(0xFF00240F),
    primaryContainer = Color(0xFF1B4B33),
    onPrimaryContainer = Color(0xFFC9F2D9),
    secondary = Color(0xFFE8B563),
    onSecondary = Color(0xFF3D2600),
    background = Color(0xFF101413),
    onBackground = Color(0xFFE6EAE8),
    surface = Color(0xFF181D1B),
    onSurface = Color(0xFFE6EAE8),
    surfaceVariant = Color(0xFF242B28),
    onSurfaceVariant = Color(0xFFB3BDB7),
    outline = Color(0xFF3A4440),
    outlineVariant = Color(0xFF2A322E),
)

@Composable
fun CarAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
