package com.metronomekiller.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/*
 * LEARN: an ImageVector is a scalable icon described by SVG-style path data
 * ("M8,5v14l11,-7z" = move to 8,5, line down 14, line to 19,12, close = a play
 * triangle). Icon() tints it with the current content colour, so one icon works
 * in light and dark mode.
 */
/** Small set of Material-style outline icons, so we don't ship the huge icons library. */
object AppIcons {
    private fun icon(name: String, path: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
            .addPath(addPathNodes(path), pathFillType = PathFillType.EvenOdd, fill = SolidColor(Color.Black))
            .build()

    val Play = icon("play", "M8,5v14l11,-7z")
    val Pause = icon("pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Add = icon("add", "M19,13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z")
    val Remove = icon("remove", "M19,13H5v-2h14v2z")
    val Back = icon("back", "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z")
    val Tune = icon(
        "tune",
        "M3,17v2h6v-2H3zM3,5v2h10V5H3zM13,21v-2h8v-2h-8v-2h-2v6h2zM7,9v2H3v2h4v2h2V9H7zM21,13v-2H11v2h10zM15,9h2V7h4V5h-4V3h-2v6z"
    )
    val List = icon(
        "list",
        "M3,13h2v-2H3v2zM3,17h2v-2H3v2zM3,9h2V7H3v2zM7,13h14v-2H7v2zM7,17h14v-2H7v2zM7,7v2h14V7H7z"
    )
    val TrendingUp = icon("trending", "M16,6l2.29,2.29 -4.88,4.88 -4,-4L2,16.59 3.41,18l6,-6 4,4 6.3,-6.29L22,12V6z")
    val Target = icon(
        "target",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8zM12,6c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6 -2.69,-6 -6,-6zM12,16c-2.21,0 -4,-1.79 -4,-4s1.79,-4 4,-4 4,1.79 4,4 -1.79,4 -4,4z"
    )
    val Tap = icon(
        "tap",
        "M12,7a5,5 0,1 0,0 10a5,5 0,1 0,0 -10zM12,15a3,3 0,1 1,0 -6a3,3 0,1 1,0 6z"
    )
    val Check = icon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z")
    val Volume = icon(
        "volume",
        "M3,9v6h4l5,5V4L7,9H3zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05c1.48,-0.73 2.5,-2.25 2.5,-4.02z"
    )
    val Metronome =icon("metronome", "M10,2h4l5,20H5L10,2zM11.6,4L7.6,20h8.8L12.4,4h-0.8zM11,9.5l1.6,0.4 -2.2,8.1 -1.6,-0.4z")
}
