package com.metronomekiller.app.metronome

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.ClickSound
import com.metronomekiller.app.engine.Meter
import com.metronomekiller.app.engine.TimeSignaturePreset
import com.metronomekiller.app.ui.AppIcons
import kotlin.math.abs

/**
 * Time signature picker: common presets, scroll wheels, rows of experimental
 * presets (odd groupings, decimal, irrational), and a fully custom input.
 *
 * LEARN: a ModalBottomSheet slides up from the bottom, so it's easy to reach
 * with a thumb, and you swipe it down to close it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeSignatureSheet(
    initial: Meter,
    currentPattern: List<BeatState>,
    onDismiss: () -> Unit,
    onConfirm: (meter: Meter, pattern: List<BeatState>?) -> Unit,
) {
    var meter by remember { mutableStateOf(initial) }
    // A preset brings its own accents; any other change keeps your current accents.
    var presetPattern by remember { mutableStateOf<List<BeatState>?>(null) }
    val preview = presetPattern ?: List(meter.beatCount) { i ->
        currentPattern.getOrNull(i) ?: if (i == 0) BeatState.ACCENT else BeatState.NORMAL
    }
    fun pick(p: TimeSignaturePreset) { meter = p.meter; presetPattern = p.pattern() }
    fun isSelected(p: TimeSignaturePreset) = p.meter == meter && preview == p.pattern()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Time signature", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                TimeSignaturePreset.common.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            PresetChip(p.label, isSelected(p), Modifier.weight(1f)) { pick(p) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Wheel(
                        items = (1..Meter.MAX_BEATS).toList(),
                        selected = meter.beatCount,
                        onSelected = { meter = meter.copy(numerator = it.toDouble()); presetPattern = null },
                        modifier = Modifier.weight(1f),
                    )
                    Text("/", fontSize = 30.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp))
                    val dens = listOf(2, 4, 8, 16)
                    Wheel(
                        items = dens,
                        selected = dens.minBy { abs(it - meter.denominator) },
                        onSelected = { meter = meter.copy(denominator = it.toDouble()); presetPattern = null },
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            PresetRow("Odd groupings", TimeSignaturePreset.additive, ::isSelected, ::pick)
            PresetRow("Decimal", TimeSignaturePreset.decimal, ::isSelected, ::pick)
            PresetRow("Irrational", TimeSignaturePreset.irrational, ::isSelected, ::pick)

            Column(Modifier.padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(12.dp))
                Text("Custom", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(6.dp))
                CustomMeterInput(meter) { meter = it; presetPattern = null }

                Spacer(Modifier.height(16.dp))
                PatternPreview(preview, meter.lastBeatFraction)
                Text(
                    describe(meter),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { onConfirm(meter, presetPattern) },
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
}

/** Plain-language explanation of what the chosen meter does. */
private fun describe(m: Meter): String {
    val parts = mutableListOf<String>()
    if (m.isIrrational) {
        val q = Meter.fmt(4.0 / m.denominator)
        parts += "Each beat is 4 ÷ ${Meter.fmt(m.denominator)} = $q quarter notes. BPM counts quarter notes."
    } else {
        parts += "BPM counts the bottom note."
    }
    if (m.lastBeatFraction < 1.0) {
        parts += "The last beat is cut to ${Meter.fmt(m.lastBeatFraction)} of a beat, then the bar starts again."
    }
    return parts.joinToString(" ")
}

/**
 * Two number fields that accept decimals. We keep the typed text separately
 * from the parsed value, so half-typed input like "3." doesn't get erased.
 */
@Composable
private fun CustomMeterInput(meter: Meter, onChange: (Meter) -> Unit) {
    var numText by remember { mutableStateOf(Meter.fmt(meter.numerator)) }
    var denText by remember { mutableStateOf(Meter.fmt(meter.denominator)) }
    // Follow outside changes (presets, wheels) unless the text already means the same number.
    LaunchedEffect(meter) {
        if (numText.toDoubleOrNull() != meter.numerator) numText = Meter.fmt(meter.numerator)
        if (denText.toDoubleOrNull() != meter.denominator) denText = Meter.fmt(meter.denominator)
    }
    val num = numText.toDoubleOrNull()
    val den = denText.toDoubleOrNull()
    val numOk = num != null && num >= 0.25 && num <= Meter.MAX_BEATS
    val denOk = den != null && den >= 0.5 && den <= 64.0

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = numText,
            onValueChange = { t ->
                numText = t.filter { it.isDigit() || it == '.' }.take(6)
                numText.toDoubleOrNull()?.let { if (it >= 0.25 && it <= Meter.MAX_BEATS && denOk) onChange(Meter(it, den!!)) }
            },
            label = { Text("Beats") },
            isError = !numOk,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        Text("/", fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp))
        OutlinedTextField(
            value = denText,
            onValueChange = { t ->
                denText = t.filter { it.isDigit() || it == '.' }.take(6)
                denText.toDoubleOrNull()?.let { if (it >= 0.5 && it <= 64.0 && numOk) onChange(Meter(num!!, it)) }
            },
            label = { Text("Note value") },
            isError = !denOk,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
    }
    if (!numOk || !denOk) {
        Text(
            "Beats 0.25 to ${Meter.MAX_BEATS}, note value 0.5 to 64",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PresetRow(
    title: String,
    presets: List<TimeSignaturePreset>,
    isSelected: (TimeSignaturePreset) -> Boolean,
    onPick: (TimeSignaturePreset) -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp),
    )
    // A row you can swipe sideways when there are more chips than fit.
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        presets.forEach { p -> PresetChip(p.label, isSelected(p)) { onPick(p) } }
    }
}

@Composable
private fun PresetChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = if (selected) ButtonDefaults.outlinedButtonColors(
        containerColor = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
    ) else ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = colors,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, maxLines = 1) }
}

/** Read-only bars showing accents; a shortened last beat is drawn narrower. */
@Composable
private fun PatternPreview(pattern: List<BeatState>, lastFraction: Double) {
    Row(
        Modifier.fillMaxWidth().height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        pattern.forEachIndexed { i, s ->
            val h = when (s) { BeatState.ACCENT -> 28.dp; BeatState.NORMAL -> 16.dp; BeatState.MUTE -> 4.dp }
            val w = if (i == pattern.lastIndex) lastFraction.toFloat() else 1f
            Box(
                Modifier.weight(w).height(h).clip(RoundedCornerShape(4.dp)).background(
                    if (s == BeatState.ACCENT) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                )
            )
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
