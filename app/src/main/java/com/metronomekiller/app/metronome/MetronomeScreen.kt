package com.metronomekiller.app.metronome

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.engine.Subdivision
import com.metronomekiller.app.ui.AppIcons
import kotlin.math.roundToInt

/**
 * The main metronome screen.
 *
 * LEARN — how Compose works: a @Composable function describes what the screen
 * looks like for the current state. `collectAsStateWithLifecycle()` subscribes
 * to the controller's StateFlow; whenever it emits a new value, Compose calls
 * this function again ("recomposition") and updates only what changed.
 * You never manually update a view — you change state, and the UI follows.
 */
@Composable
fun MetronomeScreen(
    controller: MetronomeController,
    onTogglePlay: () -> Unit,
    onOpenSettings: () -> Unit,
    onComingSoon: (String) -> Unit,
) {
    val s by controller.state.collectAsStateWithLifecycle()
    val current by controller.currentBeat.collectAsStateWithLifecycle()

    // `remember { mutableStateOf(...) }` = local UI state that survives recomposition.
    var editBpm by remember { mutableStateOf(false) }
    var editSig by remember { mutableStateOf(false) }
    var pickSound by remember { mutableStateOf(false) }

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
            Text("${s.bpm}", fontSize = 84.sp, fontWeight = FontWeight.Medium, lineHeight = 84.sp)
            Text(
                "BPM · ${MetronomeController.tempoName(s.bpm)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = { controller.nudge(-1) }) { Icon(AppIcons.Remove, "Slower") }
            Slider(
                value = s.bpm.toFloat(),
                onValueChange = { controller.setBpm(it.roundToInt()) },
                valueRange = MetronomeController.MIN_BPM.toFloat()..MetronomeController.MAX_BPM.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            OutlinedIconButton(onClick = { controller.nudge(1) }) { Icon(AppIcons.Add, "Faster") }
        }

        Spacer(Modifier.height(24.dp))

        BeatEditor(
            beats = s.beats,
            current = if (s.playing) current else -1,
            onCycle = controller::cycleBeat,
            onSet = controller::setBeat,
        )
        Text(
            "Tap a beat to change it, or swipe it up or down",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Spacer(Modifier.height(16.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            Chip("${s.beats.size}/${s.noteValue}") { editSig = true }
            Box {
                var open by remember { mutableStateOf(false) }
                Chip(s.subdivision.label) { open = true }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    Subdivision.entries.forEach { sub ->
                        DropdownMenuItem(text = { Text(sub.label) }, onClick = { controller.setSubdivision(sub); open = false })
                    }
                }
            }
            Chip(s.sound.label) { pickSound = true }
        }

        Spacer(Modifier.weight(1f))

        Row(
            Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledRound("Tap", AppIcons.Tap) { controller.tap() }
            FilledIconButton(
                onClick = onTogglePlay,
                modifier = Modifier.size(84.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(
                    if (s.playing) AppIcons.Pause else AppIcons.Play,
                    contentDescription = if (s.playing) "Stop" else "Start",
                    modifier = Modifier.size(40.dp),
                )
            }
            LabeledRound("Trainer", AppIcons.TrendingUp) { onComingSoon("Speed trainer is coming in a later build") }
        }
    }

    if (editBpm) BpmDialog(s.bpm, onDismiss = { editBpm = false }) { controller.setBpm(it); editBpm = false }
    if (editSig) TimeSignatureSheet(
        beats = s.beats.size,
        noteValue = s.noteValue,
        currentPattern = s.beats,
        onDismiss = { editSig = false },
    ) { b, n, pattern -> controller.setTimeSignature(b, n, pattern); editSig = false }
    if (pickSound) SoundSheet(
        selected = s.sound,
        onSelect = { controller.setSound(it); controller.preview(it) },
        onDismiss = { pickSound = false },
    )
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
private fun LabeledRound(label: String, icon: ImageVector, onClick: () -> Unit) {
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
    val valid = value != null && value in MetronomeController.MIN_BPM..MetronomeController.MAX_BPM
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
                supportingText = { Text("${MetronomeController.MIN_BPM} to ${MetronomeController.MAX_BPM}") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = { TextButton(onClick = { if (valid && value != null) onConfirm(value) }) { Text("Set") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
