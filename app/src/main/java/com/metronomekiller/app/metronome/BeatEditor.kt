package com.metronomekiller.app.metronome

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.metronomekiller.app.ui.rememberTick
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.ui.ink
import kotlin.math.abs

/**
 * The row of beats, Soundbrenner-style: each beat is a block of three stacked
 * segments, filled from the bottom. 0 filled = mute, 1 = normal, 2 = accent,
 * 3 = strong accent.
 *
 * - Blocks never change size, so tap targets don't move while it's clicking.
 * - The playing beat's filled segments turn the accent colour; the LEVEL is
 *   still readable, so you can see what you just set even mid-click.
 * - Tap = one level up (wraps from strong to mute). Swipe up/down = louder/quieter.
 * - Every change gives a haptic tick.
 */
@Composable
fun BeatEditor(
    beats: List<BeatState>,
    lastFraction: Float = 1f,
    current: Int,
    onCycle: (Int) -> Unit,
    onSet: (Int, BeatState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = when {
        beats.size > 10 -> 4.dp
        beats.size > 6 -> 8.dp
        else -> 18.dp
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        beats.forEachIndexed { i, state ->
            // A shortened last beat (decimal time signatures like 3.5/4) is drawn narrower.
            val w = if (i == beats.lastIndex) lastFraction.coerceIn(0.25f, 1f) else 1f
            BeatBlock(
                state = state,
                playing = i == current,
                onTap = { onCycle(i) },
                onSwipe = { up -> onSet(i, BeatState.ofLevel(state.level + if (up) 1 else -1)) },
                modifier = Modifier.weight(w),
            )
        }
    }
}

@Composable
private fun BeatBlock(
    state: BeatState,
    playing: Boolean,
    onTap: () -> Unit,
    onSwipe: (up: Boolean) -> Unit,
    modifier: Modifier,
) {
    val c = MaterialTheme.colorScheme
    val tick = rememberTick()
    // LEARN: pointerInput's gesture loop starts once and keeps running; without
    // rememberUpdatedState it would keep calling the callbacks from its first
    // composition (with a stale beat state).
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val fill by animateColorAsState(if (playing) c.primary else c.ink, tween(60), label = "beatFill")

    Column(
        modifier
            .height(64.dp)
            .pointerInput(Unit) {
                val threshold = 18.dp.toPx()
                // One gesture = finger down ... finger up. Far enough vertically = swipe, else tap.
                awaitEachGesture {
                    awaitFirstDown()
                    var dy = 0f
                    var swiped = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.first()
                        if (!swiped) {
                            dy += change.positionChange().y
                            if (abs(dy) > threshold) {
                                swiped = true
                                swipe(dy < 0) // screen y grows downward, so negative = up
                                tick()
                            }
                        }
                        change.consume()
                        if (event.changes.none { it.pressed }) break
                    }
                    if (!swiped) {
                        tap()
                        tick()
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Three separate segments with gaps between them, so the level reads as
        // stacked bars even when all three are filled. Top = level 3.
        for (seg in 3 downTo 1) {
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(if (state.level >= seg) fill else Color.Transparent)
                    .border(2.dp, if (playing && state.level >= seg) fill else c.ink, shape)
            )
        }
    }
}
