package com.metronomekiller.app.metronome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.ClickSound
import com.metronomekiller.app.engine.TimeSignaturePreset
import com.metronomekiller.app.ui.AppIcons

/**
 * Time signature picker (approved mockup): one-tap presets on top, two wheels
 * for anything else, and a live preview of the accent pattern.
 *
 * LEARN: a ModalBottomSheet slides up from the bottom — easy to reach with a thumb,
 * and swiping it down dismisses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSignatureSheet(
    beats: Int,
    noteValue: Int,
    currentPattern: List<BeatState>,
    onDismiss: () -> Unit,
    onConfirm: (beats: Int, noteValue: Int, pattern: List<BeatState>?) -> Unit,
) {
    var b by remember { mutableIntStateOf(beats) }
    var n by remember { mutableIntStateOf(noteValue) }
    // A preset brings its own accents; picking on the wheels keeps your current accents.
    var presetPattern by remember { mutableStateOf<List<BeatState>?>(null) }
    val preview = presetPattern ?: List(b) { i ->
        currentPattern.getOrNull(i) ?: if (i == 0) BeatState.ACCENT else BeatState.NORMAL
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text("Time signature", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            TimeSignaturePreset.all.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        val selected = p.beats == b && p.noteValue == n && preview == p.pattern()
                        val colors = if (selected) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ) else ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
                        OutlinedButton(
                            onClick = { b = p.beats; n = p.noteValue; presetPattern = p.pattern() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = colors,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) { Text(p.label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Wheel(
                    items = (1..16).toList(),
                    selected = b,
                    onSelected = { b = it; presetPattern = null },
                    modifier = Modifier.weight(1f),
                )
                Text("/", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp))
                Wheel(
                    items = listOf(2, 4, 8, 16),
                    selected = n,
                    onSelected = { n = it; presetPattern = null },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))
            // Read-only preview of the accents this signature will use
            Row(Modifier.fillMaxWidth().height(28.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom) {
                preview.forEach { s ->
                    val h = when (s) { BeatState.ACCENT -> 28.dp; BeatState.NORMAL -> 16.dp; BeatState.MUTE -> 4.dp }
                    Box(
                        Modifier.weight(1f).height(h).clip(RoundedCornerShape(4.dp)).background(
                            if (s == BeatState.ACCENT) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                        )
                    )
                }
            }
            Text(
                "Presets set accents too (6/8 accents 1 and 4). Use the wheels for anything else.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { onConfirm(b, n, presetPattern) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) { Text("Done") }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Sound picker (approved mockup): tap a row to choose it and hear it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(
    selected: ClickSound,
    onSelect: (ClickSound) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Sound", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "Tap to hear it. It's used right away.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            )
            ClickSound.entries.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(s) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (s == selected) Icon(AppIcons.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    else Spacer(Modifier.width(20.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(s.label, modifier = Modifier.weight(1f))
                    Icon(AppIcons.Volume, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
