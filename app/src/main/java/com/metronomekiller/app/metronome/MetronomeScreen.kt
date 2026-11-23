package com.metronomekiller.app.metronome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.ui.rememberTick
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.engine.Subdivision
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.ui.ink
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt

/**
 * The main metronome screen, laid out like the reference the user picked
 * (Soundbrenner-style): Create/Load switch, beat blocks, our wordmark, three
 * square buttons, the tempo control, and square Play / Tap buttons.
 *
 * LEARN — how Compose works: a @Composable function describes the screen for
 * the current state. `collectAsStateWithLifecycle()` subscribes to the
 * controller's StateFlow; whenever it changes, Compose calls this function
 * again ("recomposition") and redraws only what changed.
 */
@Composable
fun MetronomeScreen(
    controller: MetronomeController,
    onTogglePlay: () -> Unit,
    onLoadSong: () -> Unit,
    onComingSoon: (String) -> Unit,
) {
    val s by controller.state.collectAsStateWithLifecycle()
    val current by controller.currentBeat.collectAsStateWithLifecycle()
    val display by controller.prefs.bpmDisplay.collectAsStateWithLifecycle()
    val tick = rememberTick()

    var editBpm by remember { mutableStateOf(false) }
    var editSig by remember { mutableStateOf(false) }
    var pickSound by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        CreateLoadSwitch(onLoad = onLoadSong)

        Spacer(Modifier.height(24.dp))
        // The beat ring already shows and edits the beats, so it replaces the blocks.
        if (display != BpmDisplay.RING) {
            BeatEditor(
                beats = s.beats,
                lastFraction = s.meter.lastBeatFraction.toFloat(),
                current = if (s.playing) current else -1,
                onCycle = controller::cycleBeat,
                onSet = controller::setBeat,
            )
        }

        Spacer(Modifier.weight(1f))
        Wordmark()
        Spacer(Modifier.weight(1f))

        // Three square buttons: time signature, subdivision, more
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
            SquareButton(onClick = { editSig = true }) {
                Text(s.meter.label, fontSize = if (s.meter.label.length > 5) 14.sp else 20.sp, fontWeight = FontWeight.Medium)
            }
            Box {
                var open by remember { mutableStateOf(false) }
                SquareButton(onClick = { open = true }) { Text(subdivisionGlyph(s.subdivision), fontSize = 26.sp) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    Subdivision.entries.forEach { sub ->
                        DropdownMenuItem(
                            text = { Text("${subdivisionGlyph(sub)}   ${sub.label}") },
                            onClick = { controller.setSubdivision(sub); open = false },
                        )
                    }
                }
            }
            SquareButton(onClick = { more = true }) { Text("•••", fontSize = 18.sp) }
        }

        Spacer(Modifier.weight(1f))

        // Tempo control, with Play (left) and Tap (right) at its bottom corners.
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            TempoDisplay(display, s, if (s.playing) current else -1, controller, onEditBpm = { editBpm = true })
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SquareButton(onClick = onTogglePlay, size = 68, dark = true) {
                    Icon(
                        if (s.playing) AppIcons.Pause else AppIcons.Play,
                        contentDescription = if (s.playing) "Stop" else "Start",
                        modifier = Modifier.size(34.dp),
                    )
                }
                SquareButton(
                    onClick = {
                        controller.tap()
                        tick()
                    },
                    size = 68,
                ) { Text("Tap", fontSize = 18.sp, fontWeight = FontWeight.Medium) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (editBpm) BpmEntry(s.bpm, onDismiss = { editBpm = false }) { controller.setBpm(it); editBpm = false }
    if (editSig) TimeSignatureSheet(
        initial = s.meter,
        currentPattern = s.beats,
        onDismiss = { editSig = false },
    ) { meter, pattern -> controller.setTimeSignature(meter, pattern); editSig = false }
    if (pickSound) SoundSheet(
        selected = s.sound,
        onSelect = { controller.setSound(it); controller.preview(it) },
        onDismiss = { pickSound = false },
    )
    if (more) MoreSheet(
        controller = controller,
        onPickSound = { more = false; pickSound = true },
        onTrainer = { more = false; onComingSoon("Speed trainer is coming in a later build") },
        onDismiss = { more = false },
    )
}

private fun subdivisionGlyph(s: Subdivision) = when (s) {
    Subdivision.QUARTERS -> "♩"
    Subdivision.EIGHTHS -> "♫"
    Subdivision.TRIPLETS -> "³"
    Subdivision.SIXTEENTHS -> "♬"
}

/** Segmented switch: Create (this screen) / Load (pick a song). */
@Composable
private fun CreateLoadSwitch(onLoad: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.ink.copy(alpha = 0.45f))
            .padding(3.dp)
    ) {
        Box(
            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).background(c.ink),
            contentAlignment = Alignment.Center,
        ) { Text("Create", fontWeight = FontWeight.Medium, color = Color.White) }
        Box(
            Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp)).clickable(onClick = onLoad),
            contentAlignment = Alignment.Center,
        ) { Text("Load", color = c.onSurfaceVariant) }
    }
}

/** Our own wordmark where the reference app shows its logo. */
@Composable
private fun Wordmark() {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        // a few bars of different heights, like a sound wave
        listOf(10, 22, 40, 22, 30, 12).forEach { h ->
            Box(Modifier.padding(horizontal = 2.dp).width(3.dp).height(h.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurfaceVariant))
        }
        Spacer(Modifier.width(12.dp))
        Text("Metronome Killer", fontSize = 24.sp, fontWeight = FontWeight.Medium, color = c.onSurfaceVariant)
    }
}

/**
 * A raised square button. The soft drop shadow gives the tactile, hardware-like
 * look of the reference (a shadow, not a glow).
 */
@Composable
fun SquareButton(onClick: () -> Unit, size: Int = 64, dark: Boolean = false, content: @Composable () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier
            .size(size.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(if (dark) c.ink else c.surfaceContainerHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // The dark button always has light content, whatever the theme.
        CompositionLocalProvider(LocalContentColor provides if (dark) Color.White else c.onSurface) { content() }
    }
}

/** The "•••" sheet: sound, volume and beat feedback, without leaving the metronome. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(controller: MetronomeController, onPickSound: () -> Unit, onTrainer: () -> Unit, onDismiss: () -> Unit) {
    val s by controller.state.collectAsStateWithLifecycle()
    val volume by controller.prefs.volume.collectAsStateWithLifecycle()
    val flash by controller.prefs.flash.collectAsStateWithLifecycle()
    val vibrate by controller.prefs.vibrate.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            SheetRow("Sound", s.sound.label, onPickSound)
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text("Click volume · ${(volume * 100).roundToInt()}%")
                Slider(value = volume, onValueChange = controller::setVolume, valueRange = 0f..2f)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SheetToggle("Flash the screen", flash, controller.prefs::setFlash)
            SheetToggle("Vibrate on the beat", vibrate, controller.prefs::setVibrate)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SheetRow("Speed trainer", "Coming soon", onTrainer)
        }
    }
}

@Composable
private fun SheetRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SheetToggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Tap the BPM to type it: the screen dims, the number is selected in place and
 * the phone's number pad opens (no letters). Done (or tapping outside) sets it.
 *
 * LEARN: KeyboardType.NumberPassword is a trick to get the plain 0–9 pad on
 * almost every keyboard app; plain "Number" often adds symbols. The text isn't
 * hidden because we don't apply a password visual transformation.
 */
@Composable
private fun BpmEntry(initial: Int, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    var value by remember {
        val t = initial.toString()
        mutableStateOf(TextFieldValue(t, selection = TextRange(0, t.length))) // all selected: typing replaces it
    }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun commit() {
        val b = value.text.toIntOrNull()
        if (b != null && b in MetronomeController.MIN_BPM..MetronomeController.MAX_BPM) onConfirm(b) else onDismiss()
    }
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    Dialog(onDismissRequest = { commit() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = { commit() },
            ),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 120.dp)) {
                val shown = value.text.toIntOrNull() ?: initial
                Text(MetronomeController.tempoName(shown), style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(c.primary, c.primary.copy(alpha = 0.6f)),
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { v -> value = v.copy(text = v.text.filter(Char::isDigit).take(3)) },
                        textStyle = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, color = c.onSurface, textAlign = TextAlign.Center),
                        cursorBrush = SolidColor(c.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { commit() }),
                        modifier = Modifier.width(200.dp).focusRequester(focus),
                    )
                }
                Text(
                    "${MetronomeController.MIN_BPM}–${MetronomeController.MAX_BPM} BPM",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.onSurfaceVariant,
                )
            }
        }
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
