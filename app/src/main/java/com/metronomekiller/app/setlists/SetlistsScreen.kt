package com.metronomekiller.app.setlists

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.ui.AppIcons

/**
 * Setlists: saved tempos and time signatures in the order you play them.
 * The list of setlists, or one setlist when it's open.
 *
 * [onPlay] loads item [index] of setlist [id] (the caller switches tabs).
 */
@Composable
fun SetlistsScreen(store: SetlistStore, metronome: MetronomeController, onPlay: (String, Int) -> Unit) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    val lists by store.lists.collectAsStateWithLifecycle()
    val open = openId?.let { id -> lists.firstOrNull { it.id == id } }
    if (open != null) SetlistDetail(open, store, metronome, onBack = { openId = null }, onPlay = onPlay)
    else SetlistList(lists, store, onOpen = { openId = it })
}

@Composable
private fun SetlistList(lists: List<Setlist>, store: SetlistStore, onOpen: (String) -> Unit) {
    var naming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Setlists", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { naming = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(48.dp)) { Text("+ New setlist") }
        }
        Text(
            "Saved metronome settings, in playing order. On stage, Next jumps straight to the next one.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )
        LazyColumn {
            itemsIndexed(lists, key = { _, s -> s.id }) { _, s ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(s.id) }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.items.size} item${if (s.items.size == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
    if (naming) NameDialog("New setlist", "", "Friday gig") { name -> naming = false; name?.let { onOpen(store.create(it)) } }
}

@Composable
private fun SetlistDetail(
    list: Setlist, store: SetlistStore, metronome: MetronomeController,
    onBack: () -> Unit, onPlay: (String, Int) -> Unit,
) {
    val active by store.active.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf(false) }
    var addingPreset by remember { mutableStateOf(false) }
    var renamingItem by remember { mutableStateOf<SetItem?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") }
            Text(list.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).clickable { renaming = true })
            Box {
                var menu by remember { mutableStateOf(false) }
                TextButton(onClick = { menu = true }) { Text("•••") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = true })
                    DropdownMenuItem(text = { Text("Delete setlist") }, onClick = { menu = false; confirmDelete = true })
                }
            }
        }
        if (list.items.isNotEmpty()) Button(
            onClick = { onPlay(list.id, 0) }, shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(48.dp),
        ) { Text("Start from the top") }

        LazyColumn(Modifier.weight(1f)) {
            if (list.items.isEmpty()) item {
                Text("Add your first item: the metronome as it's set right now.",
                    color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            itemsIndexed(list.items, key = { _, it -> it.id }) { i, item ->
                val isCurrent = active?.first == list.id && active?.second == i
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (isCurrent) c.primaryContainer else c.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { onPlay(list.id, i) }.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (isCurrent) c.primary else c.onSurfaceVariant, modifier = Modifier.width(30.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Metronome · " + SetlistStore.describe(item.settings),
                            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1,
                        )
                    }
                    Box {
                        var menu by remember { mutableStateOf(false) }
                        TextButton(onClick = { menu = true }) { Text("•••") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (i > 0) DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; store.move(list.id, item.id, -1) })
                            if (i < list.items.lastIndex) DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; store.move(list.id, item.id, 1) })
                            if (item is SetItem.Preset) DropdownMenuItem(text = { Text("Update to current metronome") }, onClick = {
                                menu = false; store.updatePreset(list.id, item.id, metronome.state.value)
                            })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renamingItem = item })
                            DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; store.removeItem(list.id, item.id) })
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { addingPreset = true }, shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(48.dp)) {
                Text("+ Metronome")
            }
        }
    }

    if (renaming) NameDialog("Rename setlist", list.name, list.name) { n -> renaming = false; n?.let { store.rename(list.id, it) } }
    renamingItem?.let { item -> NameDialog("Rename", item.name, item.name) { n -> renamingItem = null; n?.let { store.renameItem(list.id, item.id, it) } } }
    if (addingPreset) {
        val s = metronome.state.value
        NameDialog(
            "Add the metronome as it's set now", "", "Intro · " + SetlistStore.describe(s),
            note = SetlistStore.describe(s) + ". Set it up on the Metronome tab first, then add it here.",
        ) { n -> addingPreset = false; n?.let { store.addPreset(list.id, it.ifBlank { SetlistStore.describe(s) }, s) } }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete \"${list.name}\"?") },
        text = { Text("The setlist goes away; your metronome settings stay.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; store.delete(list.id); onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** A small "type a name" dialog; returns null on Cancel. */
@Composable
private fun NameDialog(title: String, initial: String, placeholder: String, note: String? = null, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { onDone(null) },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(placeholder) })
                note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)) }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text("Cancel") } },
    )
}

/**
 * Shown above the Metronome tab while you play through a setlist:
 * where you are, and one-tap Previous / Next for quick changes on stage.
 */
@Composable
fun SetlistStrip(list: Setlist, index: Int, onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val item = list.items.getOrNull(index)
    val next = list.items.getOrNull(index + 1)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(10.dp))
            .background(c.surfaceVariant).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrev, enabled = index > 0) { Text("‹") }
        Column(Modifier.weight(1f)) {
            Text("${list.name} · ${index + 1} of ${list.items.size}", style = MaterialTheme.typography.labelSmall,
                color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item?.name ?: "", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (next != null) TextButton(onClick = onNext) {
            Text("Next: ${next.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 130.dp))
        } else Text("Last one", style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = onClose) { Text("×") }
    }
}
