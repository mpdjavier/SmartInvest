package com.smartinvest.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val SmartColors = darkColorScheme(
    primary = Emerald,
    onPrimary = Color(0xFF01140A),
    primaryContainer = Color(0xFF0A2A18),
    onPrimaryContainer = Color(0xFF8BFFB9),
    inversePrimary = Color(0xFF00873A),
    secondary = EmeraldSoft,
    onSecondary = Color(0xFF01140A),
    secondaryContainer = Color(0xFF0F2E1D),
    onSecondaryContainer = Color(0xFF9BFFC2),
    tertiary = Amber,
    onTertiary = Color(0xFF1A1200),
    tertiaryContainer = Color(0xFF332600),
    onTertiaryContainer = Color(0xFFFFE0A3),
    background = Background,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Emerald,
    surfaceContainerLowest = Background,
    surfaceContainerLow = Surface,
    surfaceContainer = SurfaceVariant,
    surfaceContainerHigh = SurfaceElevated,
    surfaceContainerHighest = SurfaceElevated,
    outline = Outline,
    outlineVariant = Hairline,
    error = Rose,
    onError = Color(0xFF200304),
    errorContainer = Color(0xFF3A1012),
    onErrorContainer = Color(0xFFFFD9D9),
    scrim = Color(0xFF000000),
    inverseSurface = TextPrimary,
    inverseOnSurface = Background,
)

/** Compact, nearly square corners match the terminal card language. */
private val SmartShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun SmartInvestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SmartColors,
        typography = SmartTypography,
        shapes = SmartShapes,
        content = content,
    )
}
