package com.metronomekiller.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * LEARN: Material 3 components (buttons, sliders, sheets...) read their colours
 * from a ColorScheme by role — `primary`, `surface`, `onSurface` (text drawn on
 * a surface), etc. Define the roles once here and every component follows.
 * We switch between the light and dark scheme with the phone's system setting.
 */
// Flat, neutral palette with a single warm accent. No gradients, no glow.
private val Light = lightColorScheme(
    primary = Color(0xFFD9512C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE1D8),
    onPrimaryContainer = Color(0xFF5C1C0A),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF1B1B1B),
    surface = Color(0xFFF7F7F5),
    onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFECECE8),
    onSurfaceVariant = Color(0xFF66665F),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    outline = Color(0xFFC9C9C3),
    outlineVariant = Color(0xFFE2E2DD),
    secondaryContainer = Color(0xFFECECE8),
    onSecondaryContainer = Color(0xFF1B1B1B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFF7A52),
    onPrimary = Color(0xFF2A0C02),
    primaryContainer = Color(0xFF4A2014),
    onPrimaryContainer = Color(0xFFFFDBCF),
    background = Color(0xFF111214),
    onBackground = Color(0xFFEDEDEA),
    surface = Color(0xFF111214),
    onSurface = Color(0xFFEDEDEA),
    surfaceVariant = Color(0xFF26272A),
    onSurfaceVariant = Color(0xFFA3A39D),
    surfaceContainer = Color(0xFF1A1B1E),
    surfaceContainerHigh = Color(0xFF202124),
    outline = Color(0xFF45474B),
    outlineVariant = Color(0xFF2E3033),
    secondaryContainer = Color(0xFF26272A),
    onSecondaryContainer = Color(0xFFEDEDEA),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
