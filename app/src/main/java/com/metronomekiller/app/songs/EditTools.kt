package com.metronomekiller.app.songs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Edit mode's toolbar (thumb zone), shown in place of the Click/Speed/Mix chips.
 * Fixes need a selection; Undo and "Discard edits" don't.
 */
@Composable
fun EditToolbar(
    hasSelection: Boolean,
    canUndo: Boolean,
    hasEdit: Boolean,
    onHalf: () -> Unit,
    onDouble: () -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
    onMeter: () -> Unit,
    onUndo: () -> Unit,
    onDiscard: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool("½", "Half tempo", hasSelection, Modifier.weight(1f), onHalf)
            Tool("×2", "Double", hasSelection, Modifier.weight(1f), onDouble)
            Tool("◀ 1", "Bar 1 earlier", hasSelection, Modifier.weight(1f), onEarlier)
            Tool("1 ▶", "Bar 1 later", hasSelection, Modifier.weight(1f), onLater)
            Tool("4/4", "Meter", hasSelection, Modifier.weight(1f), onMeter)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Tool(null, "Undo", canUndo, Modifier.weight(1f), onUndo)
            Tool(null, "Discard edits", hasEdit, Modifier.weight(1f), onDiscard)
        }
        if (!hasSelection) Text(
            "Select rows first. Play while editing to check each fix by ear.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
        )
    }
}

/**
 * One toolbar button. LEARN: instead of disabling a button (grey, and no clue
 * why), it stays tappable and the hint line under the toolbar says what's missing.
 */
@Composable
private fun Tool(big: String?, label: String, ready: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant)
            .clickable { if (ready) onClick() }
            .padding(vertical = if (big != null) 8.dp else 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val fg = if (ready) c.onSurface else c.onSurfaceVariant.copy(alpha = 0.6f)
        if (big != null) Text(big, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = fg)
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, maxLines = 1)
    }
}

/** The meters you can regroup bars into. Beats don't move; only the bar lines do. */
private val METERS = listOf(
    2 to 4, 3 to 4, 4 to 4, 5 to 4, 6 to 4, 7 to 4,
    3 to 8, 5 to 8, 6 to 8, 7 to 8, 9 to 8, 11 to 8,
    12 to 8, 13 to 8, 15 to 8, 5 to 16, 7 to 16, 9 to 16,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeterSheet(onDismiss: () -> Unit, onPick: (Int, Int) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Meter for the selected bars", style = MaterialTheme.typography.titleMedium)
            Text(
                "The clicks stay where they are; only how they're grouped into bars changes. " +
                    "For 7/8 and friends, each click is one eighth note.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            METERS.chunked(6).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (n, d) ->
                        Text(
                            "$n/$d", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .background(Color.Transparent)
                                .clickable { onPick(n, d) }.padding(vertical = 10.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
