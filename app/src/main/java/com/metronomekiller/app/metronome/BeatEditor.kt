package com.metronomekiller.app.metronome

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.metronomekiller.app.engine.BeatState
import kotlin.math.abs

/**
 * The row of beats you tap to set accents.
 *
 * Design (Soundbrenner-style, from user feedback on build 1):
 * - Every block is the SAME size, so tap targets never move while it's clicking.
 * - The state is how FULL a block is: full = accent, half = normal, empty = mute.
 * - The beat that's playing gets a dot underneath and a darker outline. That never
 *   changes the fill, so you can always see what you just set.
 * - Tap cycles accent → normal → mute. Swipe up/down on a block to raise/lower it.
 * - Each change gives a small vibration and the fill animates, so you know it worked.
 */
@Composable
fun BeatEditor(
    beats: List<BeatState>,
    current: Int,
    onCycle: (Int) -> Unit,
    onSet: (Int, BeatState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (beats.size > 8) 4.dp else 8.dp)) {
        beats.forEachIndexed { i, state ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                BeatBlock(
                    number = i + 1,
                    state = state,
                    playing = i == current,
                    onTap = { onCycle(i) },
                    onSwipe = { up -> onSet(i, if (up) state.louder() else state.quieter()) },
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.size(6.dp).clip(CircleShape).background(
                        if (i == current) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }
        }
    }
}

private fun BeatState.louder() = when (this) {
    BeatState.MUTE -> BeatState.NORMAL
    else -> BeatState.ACCENT
}

private fun BeatState.quieter() = when (this) {
    BeatState.ACCENT -> BeatState.NORMAL
    else -> BeatState.MUTE
}

@Composable
private fun BeatBlock(
    number: Int,
    state: BeatState,
    playing: Boolean,
    onTap: () -> Unit,
    onSwipe: (up: Boolean) -> Unit,
) {
    val c = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    // LEARN: animate*AsState smoothly moves a value toward its new target whenever
    // the target changes — here the fill height, so a change is easy to see.
    val level by animateFloatAsState(
        targetValue = when (state) { BeatState.ACCENT -> 1f; BeatState.NORMAL -> 0.5f; BeatState.MUTE -> 0f },
        animationSpec = tween(140),
        label = "beatLevel",
    )
    // LEARN: pointerInput's gesture code runs once and keeps going; without
    // rememberUpdatedState it would keep calling the callbacks from its first
    // composition (with stale beat states).
    val tap by rememberUpdatedState(onTap)
    val swipe by rememberUpdatedState(onSwipe)
    val shape = RoundedCornerShape(10.dp)

    Box(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(c.surfaceVariant)
            .border(if (playing) 2.dp else 1.dp, if (playing) c.onSurface else c.outline, shape)
            .pointerInput(Unit) {
                val threshold = 20.dp.toPx()
                // One gesture = finger down … finger up. If it travels far enough
                // vertically it's a swipe, otherwise it's a tap.
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
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        }
                        change.consume()
                        if (event.changes.none { it.pressed }) break
                    }
                    if (!swiped) {
                        tap()
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    }
                }
            },
    ) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(level)
                .background(if (state == BeatState.ACCENT) c.primary else c.primary.copy(alpha = 0.45f))
        )
        Text(
            "$number",
            style = MaterialTheme.typography.labelMedium,
            color = if (state == BeatState.ACCENT) c.onPrimary else c.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
        )
    }
}
