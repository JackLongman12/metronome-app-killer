package com.metronomekiller.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/*
 * LEARN: Material 3 components (buttons, sliders, sheets...) read their colours
 * from a ColorScheme by role: `primary` (the accent), `surface` (backgrounds),
 * `onSurface` (text drawn on a surface), `outline` (borders), etc. Define the
 * roles once and every component follows, which is how switching schemes
 * re-colours the whole app instantly.
 *
 * All schemes are flat: one neutral family plus a single accent. No gradients, no glow.
 */

private fun dark(
    bg: Long, card: Long, variant: Long, text: Long, muted: Long,
    accent: Long, onAccent: Long, accentSoft: Long, line: Long, lineSoft: Long,
) = darkColorScheme(
    primary = Color(accent), onPrimary = Color(onAccent),
    primaryContainer = Color(accentSoft), onPrimaryContainer = Color(text),
    background = Color(bg), onBackground = Color(text),
    surface = Color(bg), onSurface = Color(text),
    surfaceVariant = Color(variant), onSurfaceVariant = Color(muted),
    surfaceContainer = Color(card), surfaceContainerHigh = Color(card), surfaceContainerLow = Color(card),
    outline = Color(line), outlineVariant = Color(lineSoft),
    secondaryContainer = Color(variant), onSecondaryContainer = Color(text),
)

private fun light(
    bg: Long, card: Long, variant: Long, text: Long, muted: Long,
    accent: Long, onAccent: Long, accentSoft: Long, line: Long, lineSoft: Long,
) = lightColorScheme(
    primary = Color(accent), onPrimary = Color(onAccent),
    primaryContainer = Color(accentSoft), onPrimaryContainer = Color(text),
    background = Color(bg), onBackground = Color(text),
    surface = Color(bg), onSurface = Color(text),
    surfaceVariant = Color(variant), onSurfaceVariant = Color(muted),
    surfaceContainer = Color(card), surfaceContainerHigh = Color(card), surfaceContainerLow = Color(card),
    outline = Color(line), outlineVariant = Color(lineSoft),
    secondaryContainer = Color(variant), onSecondaryContainer = Color(text),
)

// Studio: warm dark grey with a teal accent, like a piece of studio hardware.
private val StudioScheme = dark(0xFF2A2A2C, 0xFF333335, 0xFF2F2F31, 0xFFF2F2F2, 0xFF9A9A9E,
    0xFF1FB8A6, 0xFF00201C, 0xFF17413C, 0xFF5A5A5E, 0xFF3A3A3D)
private val GraphiteScheme = dark(0xFF0F1115, 0xFF16191E, 0xFF1B1E24, 0xFFE8EAED, 0xFF9AA0A6,
    0xFF4C8DFF, 0xFF001A40, 0xFF22324F, 0xFF3A3F47, 0xFF262A31)
private val OledScheme = dark(0xFF000000, 0xFF0A0A0A, 0xFF141414, 0xFFF2F2F2, 0xFF8A8A8A,
    0xFFB8F35A, 0xFF1A2600, 0xFF2C3A14, 0xFF333333, 0xFF1F1F1F)
private val PaperScheme = light(0xFFF4F2ED, 0xFFFFFFFF, 0xFFE9E6DF, 0xFF1C1B1A, 0xFF6B6862,
    0xFF2F6B57, 0xFFFFFFFF, 0xFFD6E6DF, 0xFFC9C5BC, 0xFFDDD9D0)
private val SlateScheme = dark(0xFF151A1F, 0xFF1A2026, 0xFF1F262D, 0xFFE6ECEF, 0xFF93A1AB,
    0xFF2EC4B6, 0xFF00201D, 0xFF173B39, 0xFF3A4651, 0xFF27313A)
private val WarmLight = light(0xFFF7F7F5, 0xFFFFFFFF, 0xFFECECE8, 0xFF1B1B1B, 0xFF66665F,
    0xFFD9512C, 0xFFFFFFFF, 0xFFFBE1D8, 0xFFC9C9C3, 0xFFE2E2DD)
private val WarmDark = dark(0xFF111214, 0xFF1A1B1E, 0xFF26272A, 0xFFEDEDEA, 0xFFA3A39D,
    0xFFFF7A52, 0xFF2A0C02, 0xFF4A2014, 0xFF45474B, 0xFF2E3033)

/** The selectable colour schemes (Settings → Appearance). [swatch] is for the picker. */
enum class AppColors(val label: String, val swatchBg: Color, val swatchAccent: Color) {
    STUDIO("Studio grey and teal", Color(0xFF2A2A2C), Color(0xFF1FB8A6)),
    GRAPHITE("Graphite and blue", Color(0xFF0F1115), Color(0xFF4C8DFF)),
    OLED("OLED black and lime", Color(0xFF000000), Color(0xFFB8F35A)),
    PAPER("Paper and ink green", Color(0xFFF4F2ED), Color(0xFF2F6B57)),
    SLATE("Slate and teal", Color(0xFF151A1F), Color(0xFF2EC4B6)),
    WARM("Warm (follows system)", Color(0xFF111214), Color(0xFFFF7A52));

    /** Whether this scheme is dark, so the status bar icons can be made readable. */
    @Composable
    fun isDark(): Boolean = when (this) {
        PAPER -> false
        WARM -> isSystemInDarkTheme()
        else -> true
    }

    @Composable
    fun scheme(): ColorScheme = when (this) {
        STUDIO -> StudioScheme
        GRAPHITE -> GraphiteScheme
        OLED -> OledScheme
        PAPER -> PaperScheme
        SLATE -> SlateScheme
        WARM -> if (isSystemInDarkTheme()) WarmDark else WarmLight
    }
}

@Composable
fun AppTheme(colors: AppColors, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors.scheme(), content = content)
}
