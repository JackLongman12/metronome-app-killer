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
        Button(
            onClick = { pick.launch(arrayOf("audio/*")) },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
        ) { Text("+ Add a song from your phone") }
        Text(
            "YouTube, SoundCloud and Spotify links (and playlists) are coming next.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
        )

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
                        onRetry = { library.retry(song.id) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun SongRow(song: Song, onOpen: () -> Unit, onDelete: () -> Unit, onRetry: () -> Unit) {
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
                    "${m.mainBpm.toInt()} BPM · ${if (meters.size == 1) meters[0] else "${meters.size} meters"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { menu = true }) { Text("•••") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (song.status is SongStatus.Failed) DropdownMenuItem(text = { Text("Try again") }, onClick = { menu = false; onRetry() })
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
