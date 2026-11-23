package com.metronomekiller.app.songs

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronomekiller.app.analysis.Bar

/**
 * The "part rows" view (approved design C): one line per part, like a chord
 * chart, so the whole song fits on a screen or two.
 *  - tap a bar → jump to its "1";  hold anywhere on a row → loop that row
 *  - tap the row's name → rename it
 *  - an underline marks bars in an unusual meter (e.g. one 7/8 bar in a 4/4 song)
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PartRowsList(
    rows: List<PartRow>,
    bars: List<Bar>,
    currentBar: Int,
    playing: Boolean,
    usualMeter: String?,
    loopStart: Double?,
    modifier: Modifier = Modifier,
    /** Edit mode: the selected rows, and what a tap does instead of jumping. */
    selected: IntRange? = null,
    onSelect: ((Int) -> Unit)? = null,
    onJump: (Int) -> Unit,
    onLoop: (PartRow) -> Unit,
    onRename: (PartRow) -> Unit,
) {
    val state = rememberLazyListState()
    val currentRow = rows.indexOfLast { it.first <= currentBar.coerceAtLeast(0) }
    // LEARN: keep the playing row in view, one row of context above it.
    LaunchedEffect(currentRow, playing) {
        if (playing && currentRow >= 0) state.animateScrollToItem((currentRow - 1).coerceAtLeast(0))
    }
    val c = MaterialTheme.colorScheme
    LazyColumn(modifier, state = state, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(rows, key = { _, r -> r.first }) { i, r ->
            val looping = loopStart != null && bars.getOrNull(r.first)?.start == loopStart
            val active = i == currentRow
            val picked = selected != null && i in selected
            val tap: (Int) -> Unit = { bar -> if (onSelect != null) onSelect(i) else onJump(bar) }
            val hold: () -> Unit = { if (onSelect == null) onLoop(r) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .then(if (picked) Modifier.background(c.primaryContainer) else Modifier)
                    .then(if (looping || picked) Modifier.border(1.5.dp, c.primary, RoundedCornerShape(8.dp)) else Modifier)
                    .combinedClickable(onClick = { tap(r.first) }, onLongClick = hold)
                    .padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Name (tap to rename). Unnamed rows show their bar range.
                Column(
                    Modifier.width(84.dp).clip(RoundedCornerShape(6.dp))
                        .combinedClickable(onClick = { if (onSelect != null) onSelect(i) else onRename(r) }, onLongClick = hold)
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                ) {
                    val title = r.name ?: r.range
                    Text(
                        if (r.continued) "· $title" else title,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = when {
                            active -> c.primary
                            r.continued -> c.onSurfaceVariant
                            else -> c.onSurface
                        },
                    )
                    if (r.name != null) Text(
                        (if (r.source == NameSource.YOU) "yours · " else "") + r.range,
                        fontSize = 10.sp, lineHeight = 11.sp, color = c.onSurfaceVariant, maxLines = 1,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (k in 0 until PartRows.MAX_BARS) {
                        val b = r.first + k
                        if (b >= r.end) { Spacer(Modifier.weight(1f)); continue }
                        val bar = bars[b]
                        val odd = usualMeter != null && bar.meter != usualMeter
                        val on = b == currentBar
                        Box(
                            Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(5.dp))
                                .background(
                                    when {
                                        on -> c.primary
                                        b < currentBar -> c.surfaceVariant.copy(alpha = 0.45f)
                                        else -> c.surfaceVariant
                                    }
                                )
                                .combinedClickable(onClick = { tap(b) }, onLongClick = hold),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${b + 1}", fontSize = 10.sp, lineHeight = 11.sp,
                                    color = if (on) c.onPrimary else if (b < currentBar) c.onSurfaceVariant else c.onSurface)
                                if (odd) Text(bar.meter, fontSize = 9.sp, lineHeight = 10.sp,
                                    color = if (on) c.onPrimary else c.tertiary)
                            }
                            // unusual-meter underline
                            if (odd) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(c.tertiary))
                        }
                    }
                }
            }
        }
    }
}

/** Two-way switch between the part rows and the bar grid. */
@Composable
fun ViewSwitch(rows: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
        listOf(true to "Rows", false to "Grid").forEach { (value, label) ->
            val on = value == rows
            Text(
                label, style = MaterialTheme.typography.labelMedium,
                color = if (on) c.onPrimary else c.onSurface,
                modifier = Modifier.background(if (on) c.primary else Color.Transparent)
                    .clickable { onChange(value) }.padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
    }
}

/** Name a row ("Solo", "Verse 2"...). Saving an empty name goes back to the default. */
@Composable
fun RenameDialog(row: PartRow, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(row.first) { mutableStateOf(if (row.source == NameSource.YOU) row.name ?: "" else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name ${row.range.replaceFirstChar { it.lowercase() }}") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true,
                    placeholder = { Text(row.name ?: "Chorus") })
                Text("Leave it empty to go back to the default.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
