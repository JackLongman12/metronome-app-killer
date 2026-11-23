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
    LaunchedEffect(map) { player.setMap(map) } // switching AI <-> tab swaps the clicks live
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
    val rowsView by prefs.partRows.collectAsStateWithLifecycle()
    val loop by player.loop.collectAsStateWithLifecycle()
    val loopRepeats by player.loopRepeats.collectAsStateWithLifecycle()
    // "Speed up" practice: a looped part starts at 70 % and gets 5 % faster each time round, up to 100 %.
    var rampOn by remember { mutableStateOf(false) }
    LaunchedEffect(rampOn, loop, loopRepeats) {
        if (rampOn && loop != null) player.setSpeed((RAMP_START + RAMP_STEP * loopRepeats).coerceAtMost(1f))
    }
    var renaming by remember { mutableStateOf<PartRow?>(null) }
    // Edit mode: a selected stretch of rows (first..last row index) and an undo history
    var editing by remember { mutableStateOf(false) }
    var selectionRaw by remember { mutableStateOf<IntRange?>(null) }
    val undo = remember { androidx.compose.runtime.mutableStateListOf<com.metronomekiller.app.analysis.TempoMap>() }
    var showMeter by remember { mutableStateOf(false) }

    // The metronome and the song shouldn't click over each other.
    LaunchedEffect(playing) { if (playing) metronome.stop() }

    val parts = map.sections
    val currentPart = parts.indexOfLast { it.start <= pos + 0.001 }.coerceAtLeast(0)
    var selectedPart by remember { mutableIntStateOf(0) }
    LaunchedEffect(currentPart) { selectedPart = currentPart } // follow playback
    val bars = map.bars
    val metersVary = remember(map) { bars.map { it.meter }.distinct().size > 1 }
    val currentBar = bars.indexOfLast { it.start <= pos + 0.02 }
    val rows = remember(map, song.rowNames) { PartRows.build(map, song.rowNames) }
    val usualMeter = remember(map) { PartRows.usualMeter(map) }
    val currentRow = rows.indexOfLast { it.first <= currentBar.coerceAtLeast(0) }
    val showRows = rowsView || editing // editing always works on the part rows
    // A selection only makes sense for the rows it was made on: if the map changed
    // underneath (switching AI/tab/yours, Discard), a stale one would point past the end.
    val selection: IntRange? = selectionRaw?.takeIf { it.first >= 0 && it.last < rows.size }

    /**
     * Apply one fix to the selected bars, keep the old map for Undo, save, and
     * re-select the same stretch of time (bar numbers may have changed).
     */
    fun applyFix(fix: (com.metronomekiller.app.analysis.TempoMap, Int, Int) -> com.metronomekiller.app.analysis.TempoMap) {
        val sel = selection ?: return
        val a = rows[sel.first].first
        val b = rows[sel.last].end
        val t0 = bars[a].start
        val t1 = bars.getOrNull(b)?.start ?: Double.MAX_VALUE
        val fixed = fix(map, a, b)
        if (fixed === map) return
        undo += map
        library.saveEdit(song.id, fixed)
        val newRows = PartRows.build(fixed, emptyMap())
        val inside = newRows.indices.filter {
            val t = fixed.bars[newRows[it].first].start
            t >= t0 - 1e-6 && t < t1 - 1e-6
        }
        selectionRaw = if (inside.isEmpty()) null else inside.first()..inside.last()
        tick()
    }

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

        // Where this tempo map came from, with one-tap switches to the others
        if (song.tabInfo != null || song.hasEdit) {
            val info = song.tabInfo
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when {
                        song.usingEdit -> "Your edited map"
                        song.usingTab -> "Songsterr tab: $info"
                        else -> "AI map"
                    },
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                val others = buildList {
                    if (song.usingTab || song.usingEdit) add("AI" to SongLibrary.Source.AI)
                    if (info != null && !song.usingTab) add("tab" to SongLibrary.Source.TAB)
                    if (song.hasEdit && !song.usingEdit) add("yours" to SongLibrary.Source.EDIT)
                }
                others.forEach { (label, src) ->
                    Text(
                        "Use $label",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { player.pause(); undo.clear(); library.useMap(song.id, src) }.padding(start = 10.dp),
                    )
                }
            }
        }

        // Current tempo readout
        val sec = parts.getOrNull(currentPart)
        Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                when {
                    sec == null || sec.kind == Section.Kind.FREE -> "free time"
                    else -> "${if (rowsView) rows.getOrNull(currentRow)?.let { it.name ?: it.range } ?: "Part ${currentPart + 1}" else "Part ${currentPart + 1}"} · " +
                        "${bars.getOrNull(currentBar)?.meter ?: sec.meter}${if (sec.kind == Section.Kind.RAMP) " · changing tempo" else ""}"
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

        // Bars: part rows (default) or the bar grid; the switch is remembered
        if (editing) Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val sel = selection
            Text(
                if (sel == null) "Editing · tap the rows that are wrong"
                else "Editing · bars ${rows[sel.first].first + 1}–${rows[sel.last].end}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
            )
            Text("All", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { selectionRaw = if (rows.isEmpty()) null else rows.indices }.padding(horizontal = 8.dp))
            Text("Done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { editing = false; selectionRaw = null }.padding(start = 8.dp))
        } else Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (rowsView) "Parts · tap a bar to jump, hold to loop" else "Parts · tap to jump",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            ViewSwitch(rowsView) { prefs.setPartRows(it) }
        }
        loop?.let { (a, _) ->
            val r = rows.firstOrNull { bars.getOrNull(it.first)?.start == a }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Looping ${r?.let { it.name ?: it.range } ?: "a part"}", style = MaterialTheme.typography.labelMedium)
                    Text(
                        if (rampOn) "Speeding up: ${(speed * 100).toInt()}% · round ${loopRepeats + 1}" else "Round ${loopRepeats + 1}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (rampOn) "Steady speed" else "Speed up", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        rampOn = !rampOn
                        if (!rampOn) player.setSpeed(1f)
                        tick()
                    }.padding(start = 8.dp))
                Text("Stop", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        player.clearLoop()
                        if (rampOn) { rampOn = false; player.setSpeed(1f) }
                        tick()
                    }.padding(start = 12.dp))
            }
        }
        if (showRows) PartRowsList(
            rows = rows, bars = bars, currentBar = currentBar, playing = playing,
            usualMeter = usualMeter, loopStart = loop?.first,
            selected = if (editing) selection else null,
            onSelect = if (!editing) null else { i ->
                // first tap picks a row; later taps stretch the selection to include the tapped row
                val sel = selection
                selectionRaw = when {
                    sel == null -> i..i
                    i in sel -> i..i
                    else -> minOf(sel.first, i)..maxOf(sel.last, i)
                }
                tick()
            },
            modifier = Modifier.weight(1f),
            onJump = { jump(bars[it].start) },
            onLoop = { r ->
                val a = bars[r.first].start
                val b = bars.getOrNull(r.end)?.start ?: map.duration
                if (loop?.first == a) player.clearLoop() else player.setLoop(a, b)
                tick()
            },
            onRename = { renaming = it },
        )
        if (!showRows) Column(Modifier.weight(1f)) {
        // Parts (the "Parts" label is in the header row above)
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
                    Modifier.height(if (metersVary) 42.dp else 36.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                        .clickable { jump(b.value.start) },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${b.index + 1}", style = MaterialTheme.typography.labelMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
                        // songs that change meter show each bar's meter (e.g. 7/8) under its number
                        if (metersVary) Text(b.value.meter, fontSize = 9.sp, lineHeight = 9.sp,
                            color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        }
        // Transport (thumb zone)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareButton(onClick = {
                if (rowsView) {
                    // like a CD player: back to the start of this row, or the previous one if you're at its start
                    val r = rows.getOrNull(currentRow)
                    val target = if (r != null && pos - bars[r.first].start > 1.0) r else rows.getOrNull(currentRow - 1)
                    target?.let { jump(bars[it.first].start) } ?: jump(0.0)
                } else parts.getOrNull(currentPart - 1)?.let { jump(it.start) } ?: jump(0.0)
            }, size = 56) {
                Text("⏮", fontSize = 20.sp)
            }
            SquareButton(onClick = player::toggle, size = 68, dark = true) {
                Icon(if (playing) AppIcons.Pause else AppIcons.Play, contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(34.dp))
            }
            SquareButton(onClick = {
                if (rowsView) rows.getOrNull(currentRow + 1)?.let { jump(bars[it.first].start) }
                else parts.getOrNull(currentPart + 1)?.let { jump(it.start) }
            }, size = 56) {
                Text("⏭", fontSize = 20.sp)
            }
        }
        if (song.skips.isNotEmpty()) Text(
            "Skipping ${song.skips.size} non-music part${if (song.skips.size > 1) "s" else ""} (SponsorBlock)",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        renaming?.let { r ->
            RenameDialog(r, onDismiss = { renaming = null }) { name -> library.renameRow(song.id, r.first, name); renaming = null }
        }
        if (showMix) MixSheet(
            songLevel, clickLevel, prefs::setSongLevel, prefs::setSongClickLevel,
            offsetMs = song.offsetMs, onOffset = { library.setOffset(song.id, it) },
        ) { showMix = false }
        if (showMeter) MeterSheet(onDismiss = { showMeter = false }) { n, d ->
            showMeter = false
            applyFix { m, a, b -> com.metronomekiller.app.analysis.MapEditor.setMeter(m, a, b, n, d) }
        }
        if (editing) EditToolbar(
            hasSelection = selection != null,
            canUndo = undo.isNotEmpty(),
            hasEdit = song.hasEdit,
            onHalf = { applyFix(com.metronomekiller.app.analysis.MapEditor::halfTempo) },
            onDouble = { applyFix(com.metronomekiller.app.analysis.MapEditor::doubleTempo) },
            onEarlier = { applyFix { m, a, b -> com.metronomekiller.app.analysis.MapEditor.shiftDownbeat(m, a, b, -1) } },
            onLater = { applyFix { m, a, b -> com.metronomekiller.app.analysis.MapEditor.shiftDownbeat(m, a, b, +1) } },
            onMeter = { showMeter = true },
            onUndo = {
                undo.removeLastOrNull()?.let { prev -> library.saveEdit(song.id, prev) }
                selectionRaw = null; tick()
            },
            onDiscard = { undo.clear(); selectionRaw = null; library.discardEdit(song.id); tick() },
        ) else Row(
            // scrolls sideways on narrow phones so no chip (like Edit) gets squeezed off the screen
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            Chip(if (clickOn) "Click on" else "Click off", clickOn) { player.setClickOn(!clickOn) }
            Chip("Speed ${(speed * 100).toInt()}%", speed != 1f) { player.cycleSpeed() }
            Chip("Mix", showMix) { showMix = true }
            Chip("Edit", false) { editing = true; player.clearLoop() }
        }
    }
}

private const val RAMP_START = 0.7f
private const val RAMP_STEP = 0.05f

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
