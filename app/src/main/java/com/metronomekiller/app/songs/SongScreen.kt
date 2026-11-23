package com.metronomekiller.app.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.analysis.Section
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.metronome.SquareButton
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.ui.rememberTick

/**
 * One song's tempo map, built for one-handed use (approved design):
 * - top: the current part's tempo and meter, and which bar you're in
 * - a scrub bar for the whole song
 * - PARTS strip: tap a part to jump to it
 * - BARS grid for the selected part: tap a bar to jump exactly to its "1"
 * - bottom (thumb zone): previous part / play / next part, click on/off, speed
 */
@Composable
fun SongScreen(song: Song, metronome: MetronomeController, library: SongLibrary, onBack: () -> Unit) {
    val map = song.map ?: return
    val context = LocalContext.current
    val tick = rememberTick()
    val player = remember(song.id) { SongPlayer(context).also { it.load(song.uri, map); it.setSkips(song.skips) } }
    DisposableEffect(player) { onDispose { player.release() } }

    val pos by player.position.collectAsStateWithLifecycle()
    val playing by player.playing.collectAsStateWithLifecycle()
    val clickOn by player.clickOn.collectAsStateWithLifecycle()
    val speed by player.speed.collectAsStateWithLifecycle()
    val prefs = metronome.prefs
    val songLevel by prefs.songLevel.collectAsStateWithLifecycle()
    val clickLevel by prefs.songClickLevel.collectAsStateWithLifecycle()
    LaunchedEffect(songLevel, clickLevel) { player.setLevels(songLevel, clickLevel) }
    LaunchedEffect(song.offsetMs) { player.setOffsetMs(song.offsetMs) }
    var showMix by remember { mutableStateOf(false) }

    // The metronome and the song shouldn't click over each other.
    LaunchedEffect(playing) { if (playing) metronome.stop() }

    val parts = map.sections
    val currentPart = parts.indexOfLast { it.start <= pos + 0.001 }.coerceAtLeast(0)
    var selectedPart by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentPart) { selectedPart = currentPart } // follow playback
    val bars = map.bars
    val currentBar = bars.indexOfLast { it.start <= pos + 0.02 }

    fun jump(sec: Double) { player.seekTo(sec.coerceAtLeast(0.0)); tick() }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = { player.pause(); onBack() }) { Icon(AppIcons.Back, "Back") }
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.artist.isNotBlank()) Text(song.artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Current tempo readout
        val sec = parts.getOrNull(currentPart)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    sec == null || sec.kind == Section.Kind.FREE -> "free time"
                    else -> "Part ${currentPart + 1} · ${sec.meter}${if (sec.kind == Section.Kind.RAMP) " · changing tempo" else ""}"
                },
                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (sec == null || sec.kind == Section.Kind.FREE) "–" else sec.bpm.toInt().toString(),
                fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 52.sp,
            )
            Text(
                "${if (currentBar >= 0) "Bar ${currentBar + 1} of ${bars.size}" else "Before bar 1"} · ${fmt(pos)} / ${fmt(map.duration)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Scrub bar with part boundaries drawn underneath
        Slider(
            value = pos.toFloat().coerceIn(0f, map.duration.toFloat()),
            onValueChange = { player.seekTo(it.toDouble()) },
            valueRange = 0f..map.duration.toFloat().coerceAtLeast(1f),
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp))) {
            parts.forEachIndexed { i, p ->
                Box(
                    Modifier.weight(((p.end - p.start) / map.duration).toFloat().coerceAtLeast(0.001f)).height(8.dp)
                        .background(
                            when {
                                p.kind == Section.Kind.FREE -> MaterialTheme.colorScheme.surfaceVariant
                                i == currentPart -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.primary.copy(alpha = if (i % 2 == 0) 0.35f else 0.55f)
                            }
                        )
                )
            }
        }

        // Parts
        Text("Parts", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            parts.forEachIndexed { i, p ->
                val on = i == selectedPart
                Column(
                    Modifier.width(78.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { selectedPart = i; jump(p.start) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                    Text(if (p.kind == Section.Kind.FREE) "Free" else "Part ${i + 1}", color = fg, style = MaterialTheme.typography.labelLarge)
                    Text(if (p.kind == Section.Kind.FREE) fmt(p.start) else "${p.bpm.toInt()} · ${p.meter}",
                        color = fg, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Bars of the selected part
        val part = parts.getOrNull(selectedPart)
        val partBars = bars.withIndex().filter { part != null && it.value.start >= part.start - 0.05 && it.value.start < part.end - 0.05 }
        Text(
            if (partBars.isEmpty()) "Bars" else "Bars ${partBars.first().index + 1}–${partBars.last().index + 1} · tap to jump",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(8),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            itemsIndexed(partBars, key = { _, b -> b.index }) { _, b ->
                val on = b.index == currentBar
                Box(
                    Modifier.height(36.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .clickable { jump(b.value.start) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${b.index + 1}", style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }

        // Transport (thumb zone)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareButton(onClick = { parts.getOrNull(currentPart - 1)?.let { jump(it.start) } ?: jump(0.0) }, size = 56) {
                Text("⏮", fontSize = 20.sp)
            }
            SquareButton(onClick = player::toggle, size = 68, dark = true) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(34.dp))
            }
            SquareButton(onClick = { parts.getOrNull(currentPart + 1)?.let { jump(it.start) } }, size = 56) {
                Text("⏭", fontSize = 20.sp)
            }
        }
        if (song.skips.isNotEmpty()) Text(
            "Skipping ${song.skips.size} non-music part${if (song.skips.size > 1) "s" else ""} (SponsorBlock)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (showMix) MixSheet(
            songLevel, clickLevel, prefs::setSongLevel, prefs::setSongClickLevel,
            offsetMs = song.offsetMs, onOffset = { library.setOffset(song.id, it) },
        ) { showMix = false }
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            Chip(if (clickOn) "Click on" else "Click off", clickOn) { player.setClickOn(!clickOn) }
            Chip("Speed ${(speed * 100).toInt()}%", speed != 1f) { player.cycleSpeed() }
            Chip("Mix", showMix) { showMix = true }
        }
    }
}

/** Song and click volume, independently. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MixSheet(
    song: Float, click: Float, onSong: (Float) -> Unit, onClick: (Float) -> Unit,
    offsetMs: Int, onOffset: (Int) -> Unit, onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Mix", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("Song volume · ${(song * 100).toInt()}%")
            Slider(value = song, onValueChange = onSong, valueRange = 0f..1f)
            Text("Click volume · ${(click * 100).toInt()}%")
            Slider(value = click, onValueChange = onClick, valueRange = 0f..2f)
            Spacer(Modifier.height(8.dp))
            Text("Click timing · ${if (offsetMs > 0) "+" else ""}$offsetMs ms")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Earlier", false) { onOffset((offsetMs - 10).coerceAtLeast(-300)) }
                Chip("Reset", false) { onOffset(0) }
                Chip("Later", false) { onOffset((offsetMs + 10).coerceAtMost(300)) }
            }
            Text(
                "If the click sounds a little early or late against this song, nudge it here (10 ms per tap). Saved per song.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Text(
                "Tip: turn the song down to hear your own playing against the click.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
            .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge,
            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
    }
}

private fun fmt(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
