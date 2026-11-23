package com.metronomekiller.app.songs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.analysis.Section

/**
 * Your songs. "Add song" opens the phone's file picker for audio files; the song
 * stays where it is and gets analysed in the background.
 *
 * LEARN: OpenDocument is Android's system file picker. Asking for audio files
 * shows music from your phone, Downloads, Drive and so on, with no storage
 * permission needed: the user's pick is the permission.
 * (Note for readers: Kotlin allows comments inside comments, so writing the
 * audio MIME pattern with its star inside a comment would open a new one.)
 */
@Composable
fun SongsScreen(library: SongLibrary, onOpen: (String) -> Unit) {
    val songs by library.songs.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) library.add(uri)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Songs", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        // Paste a YouTube / SoundCloud link (a video or a whole playlist)
        val linkStatus by library.linkStatus.collectAsStateWithLifecycle()
        var link by remember { mutableStateOf("") }
        val clipboard = LocalClipboardManager.current
        var tabFor by remember { mutableStateOf<Song?>(null) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; library.clearLinkStatus() },
                placeholder = { Text("Paste a YouTube, SoundCloud or Songsterr link") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (link.isNotBlank()) { library.addLink(link); link = "" } }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val text = link.ifBlank { clipboard.getText()?.text ?: "" }
                    if (text.isNotBlank()) { library.addLink(text); link = "" }
                },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.height(56.dp),
            ) { Text(if (link.isBlank()) "Paste" else "Add") }
        }
        linkStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("Couldn't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { pick.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("+ Add a song from your phone") }
        Text(
            "Playlist links add every song. A Songsterr link adds the song with that exact tab. You can also share a video from the YouTube app to here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )

        tabFor?.let { s ->
            TabLinkDialog(s, clipboard.getText()?.text, onDismiss = { tabFor = null }) { library.attachTab(s.id, it); tabFor = null }
        }
        if (songs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Add a song and the app maps its tempo, tempo changes and bar lines, so the click follows the music.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn {
                items(songs, key = { it.id }) { song ->
                    SongRow(song, onOpen = { if (song.map != null) onOpen(song.id) }, onDelete = { library.remove(song.id) },
                        onRetry = { library.retry(song.id) }, onPickTab = { tabFor = song })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, onOpen: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit, onPickTab: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (song.artist.isNotBlank()) Text(song.artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            song.map?.let { m ->
                val meters = m.sections.filter { it.kind != Section.Kind.FREE }.map { it.meter }.distinct()
                Text(
                    (if (song.usingTab) "TAB · " else "") +
                        "${m.mainBpm.toInt()} BPM · ${if (meters.size == 1) meters[0] else "${meters.size} meters"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("•••") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (song.status is SongStatus.Failed) "Try again" else "Analyse again") },
                        onClick = { menu = false; onRetry() },
                    )
                    if (song.map != null) DropdownMenuItem(text = { Text("Use a Songsterr tab") }, onClick = { menu = false; onPickTab() })
                    DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; onDelete() })
                }
            }
        }
        when (val s = song.status) {
            is SongStatus.Working -> {
                Text(s.step, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            SongStatus.Queued -> Text("Waiting to analyse…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            is SongStatus.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SongStatus.Ready -> Unit
        }
    }
}

/**
 * Paste the link of the Songsterr tab you trust for this song (open the tab on
 * songsterr.com, Share → Copy link). If your clipboard already has one, it's filled in.
 */
@Composable
private fun TabLinkDialog(song: Song, clip: String?, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    var text by remember { mutableStateOf(clip?.takeIf { com.metronomekiller.app.links.Songsterr.songIdFromLink(it) != null } ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Songsterr tab for ${song.title}") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, singleLine = true,
                    placeholder = { Text("songsterr.com/a/wsa/…-tab-s269") })
                Text(
                    "The tab has to line up with this recording; if it doesn't, nothing changes. " +
                        "Its part names (Intro, Verse...) show up in the part rows.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUse(text) }, enabled = text.isNotBlank()) { Text("Use tab") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
