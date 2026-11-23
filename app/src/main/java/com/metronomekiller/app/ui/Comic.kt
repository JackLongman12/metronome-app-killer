package com.metronomekiller.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronomekiller.app.R

/*
 * The optional "Comic book" theme: black ink on white paper, thick outlines,
 * hard offset shadows (not soft ones), halftone dots for shading, and comic
 * lettering. It's chosen in Settings → Appearance; Studio stays the default.
 *
 * LEARN — CompositionLocal: a value that any composable below can read
 * without passing it through every function. AppTheme provides LocalComic,
 * and components like SquareButton check it to draw themselves comic-style.
 */

/** True while the Comic book theme is active. */
val LocalComic = staticCompositionLocalOf { false }

/** Comic lettering for headings (Bangers) and text (Comic Neue), both SIL Open Font License. */
val Bangers = FontFamily(Font(R.font.bangers))
val ComicNeue = FontFamily(Font(R.font.comic_neue_bold, FontWeight.Bold))

fun comicTypography(): Typography {
    val t = Typography()
    fun head(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = Bangers, fontWeight = FontWeight.Normal, letterSpacing = 1.sp)
    fun body(s: androidx.compose.ui.text.TextStyle) = s.copy(fontFamily = ComicNeue, fontWeight = FontWeight.Bold)
    return t.copy(
        displayLarge = head(t.displayLarge), displayMedium = head(t.displayMedium), displaySmall = head(t.displaySmall),
        headlineLarge = head(t.headlineLarge), headlineMedium = head(t.headlineMedium), headlineSmall = head(t.headlineSmall),
        titleLarge = head(t.titleLarge), titleMedium = head(t.titleMedium), titleSmall = head(t.titleSmall),
        bodyLarge = body(t.bodyLarge), bodyMedium = body(t.bodyMedium), bodySmall = body(t.bodySmall),
        labelLarge = body(t.labelLarge), labelMedium = body(t.labelMedium), labelSmall = body(t.labelSmall),
    )
}

/**
 * A comic "panel": thick black border plus a hard black shadow offset down-right,
 * like printed ink. The shadow is drawn behind the shape, outside its bounds.
 */
fun Modifier.comicPanel(shape: Shape, fill: Color = Color.White, border: Dp = 2.5.dp, offset: Dp = 3.dp): Modifier =
    this
        .drawBehind {
            val o = offset.toPx()
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(o, o) { drawOutline(outline, Color.Black) }
        }
        .clip(shape)
        .background(fill)
        .border(border, Color.Black, shape)

/**
 * Halftone dots as a repeating brush: one dot in a small tile, tiled forever.
 *
 * LEARN: an ImageShader repeats a tiny bitmap across any area it fills, which
 * is far cheaper than drawing thousands of circles one by one.
 */
@Composable
fun rememberHalftone(spacing: Dp = 6.dp, radius: Dp = 1.4.dp, dot: Color = Color.Black, paper: Color = Color.White): Brush {
    val density = LocalDensity.current
    return remember(spacing, radius, dot, paper, density) {
        val s = with(density) { spacing.toPx() }.toInt().coerceAtLeast(3)
        val r = with(density) { radius.toPx() }
        val tile = ImageBitmap(s, s)
        Canvas(tile).apply {
            drawRect(0f, 0f, s.toFloat(), s.toFloat(), Paint().apply { color = paper })
            drawCircle(Offset(s / 2f, s / 2f), r, Paint().apply { color = dot; isAntiAlias = true })
        }
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

/** Shortcut: is the comic theme on? */
@Composable
fun isComic(): Boolean = LocalComic.current

/** Comic heading style for big labels, falls back to the normal style otherwise. */
@Composable
fun headingStyle() = MaterialTheme.typography.titleMedium
