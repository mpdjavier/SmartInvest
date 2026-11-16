package com.smartinvest.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.smartinvest.R

/**
 * IBM Plex Mono carries the terminal voice of the app (wordmark, labels, data,
 * big figures). IBM Plex Sans is reserved for prose so long copy stays readable.
 */
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
)

val PlexSans = FontFamily(
    Font(R.font.ibm_plex_sans_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_semibold, FontWeight.SemiBold),
)

val SmartTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 44.sp,
        lineHeight = 50.sp,
        letterSpacing = (-1.2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.8).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 29.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.6).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = PlexSans,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = PlexSans,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = PlexSans,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.08.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.10.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.12.sp,
    ),
)
