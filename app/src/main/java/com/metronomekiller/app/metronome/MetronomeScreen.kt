package com.metronomekiller.app.metronome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.ui.AppIcons
import kotlin.math.roundToInt

@Composable
fun MetronomeScreen(
    vm: MetronomeViewModel,
    onOpenSettings: () -> Unit,
    onComingSoon: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val current by vm.currentBeat.collectAsStateWithLifecycle()
    var editBpm by remember { mutableStateOf(false) }
    var editSig by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Metronome", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenSettings) { Icon(AppIcons.Tune, contentDescription = "Settings") }
        }

        Spacer(Modifier.weight(1f))

        // Tempo readout — tap to type an exact value
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editBpm = true }.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${ui.bpm}", fontSize = 84.sp, fontWeight = FontWeight.Medium, lineHeight = 84.sp)
            Text(
                "BPM · ${MetronomeViewModel.tempoName(ui.bpm)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = { vm.nudge(-1) }) { Icon(AppIcons.Remove, "Slower") }
            Slider(
                value = ui.bpm.toFloat(),
                onValueChange = { vm.setBpm(it.roundToInt()) },
                valueRange = MetronomeViewModel.MIN_BPM.toFloat()..MetronomeViewModel.MAX_BPM.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            OutlinedIconButton(onClick = { vm.nudge(1) }) { Icon(AppIcons.Add, "Faster") }
        }

        Spacer(Modifier.height(28.dp))

        BeatRow(ui.beats, if (ui.playing) current else -1, vm::cycleBeat)
        Text(
            "Tap a beat: accent, normal, mute",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Chip("${ui.beats.size}/${ui.noteValue}") { editSig = true }
            Box {
                var open by remember { mutableStateOf(false) }
                Chip(ui.subdivision.label) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    Subdivision.entries.forEach { s ->
                        DropdownMenuItem(text = { Text(s.label) }, onClick = { vm.setSubdivision(s); open = false })
                    }
                }
            }
            Chip(ui.sound.label) { vm.cycleSound() }
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledRound("Tap", AppIcons.Tap) { vm.tap() }
            FilledIconButton(
                onClick = vm::togglePlay,
                modifier = Modifier.size(84.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(
                    if (ui.playing) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (ui.playing) "Stop" else "Start",
                    modifier = Modifier.size(40.dp),
                )
            }
            LabeledRound("Trainer", AppIcons.TrendingUp) { onComingSoon("Speed trainer is coming in a later build") }
        }
    }

    if (editBpm) BpmDialog(ui.bpm, onDismiss = { editBpm = false }) { vm.setBpm(it); editBpm = false }
    if (editSig) TimeSignatureDialog(ui.beats.size, ui.noteValue, onDismiss = { editSig = false }) { b, n ->
        vm.setTimeSignature(b, n); editSig = false
    }
}

@Composable
private fun BeatRow(beats: List<BeatState>, current: Int, onTap: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        beats.forEachIndexed { i, state ->
            val active = i == current
            val shape = RoundedCornerShape(8.dp)
            val (h, fill, border) = when (state) {
                BeatState.ACCENT -> Triple(64.dp, c.primary, Color.Transparent)
                BeatState.NORMAL -> Triple(44.dp, c.primaryContainer, Color.Transparent)
                BeatState.MUTE -> Triple(44.dp, Color.Transparent, c.outline)
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(h)
                    .clip(shape)
                    .background(if (active) c.onSurface else fill)
                    .border(1.dp, if (active) c.onSurface else border, shape)
                    .clickable { onTap(i) }
            )
        }
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Text(label, color = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun LabeledRound(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedIconButton(onClick = onClick, modifier = Modifier.size(56.dp), shape = CircleShape) {
            Icon(icon, contentDescription = label)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BpmDialog(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by remember { mutableStateOf(initial.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && value in MetronomeViewModel.MIN_BPM..MetronomeViewModel.MAX_BPM
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set tempo") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text("BPM") },
                singleLine = true,
                isError = !valid,
                supportingText = { Text("${MetronomeViewModel.MIN_BPM} to ${MetronomeViewModel.MAX_BPM}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { if (valid) onConfirm(value!!) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimeSignatureDialog(beats: Int, noteValue: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var b by remember { mutableIntStateOf(beats) }
    var n by remember { mutableIntStateOf(noteValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time signature") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedIconButton(onClick = { if (b > 1) b-- }) { Icon(AppIcons.Remove, "Fewer beats") }
                    Text("$b/$n", fontSize = 40.sp, fontWeight = FontWeight.Medium, modifier = Modifier.width(120.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    OutlinedIconButton(onClick = { if (b < 16) b++ }) { Icon(AppIcons.Add, "More beats") }
                }
                Spacer(Modifier.height(16.dp))
                val options = listOf(2, 4, 8, 16)
                SingleChoiceSegmentedButtonRow {
                    options.forEachIndexed { i, v ->
                        SegmentedButton(
                            selected = n == v,
                            onClick = { n = v },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text("$v") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(b, n) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
