package com.metronomekiller.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.metronome.MetronomeScreen
import com.metronomekiller.app.settings.CalibrationScreen
import com.metronomekiller.app.settings.SettingsScreen
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.ui.AppTheme
import com.metronomekiller.app.update.UpdateState
import com.metronomekiller.app.update.UpdateViewModel
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    METRONOME("Metronome", AppIcons.Metronome),
    SONGS("Songs", AppIcons.Music),
    PRACTICE("Practice", AppIcons.Target),
    SETLISTS("Setlists", AppIcons.List),
}

private enum class Screen { MAIN, SETTINGS, CALIBRATION }

/**
 * The app's only Activity (a "window" Android gives us). All screens are
 * Compose functions swapped inside it, which keeps navigation simple.
 *
 * LEARN: `by viewModels()` creates the UpdateViewModel once and hands back the
 * same one after rotation, so an in-progress download isn't lost.
 */
class MainActivity : ComponentActivity() {
    private val metronome get() = App.from(this).metronome
    private val updates: UpdateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableStateOf(Tab.METRONOME) }
        var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
        val prefs = metronome.prefs

        // Android 13+ needs permission to show the "metronome is playing" notification.
        // We ask once, the first time you press play. Playback works either way.
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val togglePlay = {
            if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                prefs.askedNotifications = true
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            metronome.togglePlay()
        }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

        // Keep the screen on while the click is running.
        val playing = metronome.state.collectAsStateWithLifecycle().value.playing
        LaunchedEffect(playing) {
            if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        BackHandler(enabled = screen != Screen.MAIN) {
            screen = if (screen == Screen.CALIBRATION) Screen.SETTINGS else Screen.MAIN
        }

        // Full-screen flash on each beat (optional). A flat tint that fades fast — no glow.
        val flashOn by prefs.flash.collectAsStateWithLifecycle()
        val flashAlpha = remember { Animatable(0f) }
        var flashAccent by remember { mutableStateOf(false) }
        LaunchedEffect(flashOn) {
            if (!flashOn) return@LaunchedEffect
            metronome.beatEvents.collect { e ->
                if (e.state == BeatState.MUTE) return@collect
                flashAccent = e.state == BeatState.ACCENT
                launch {
                    flashAlpha.snapTo(if (flashAccent) 0.35f else 0.14f)
                    flashAlpha.animateTo(0f, tween(160))
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (screen == Screen.MAIN) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (screen == Screen.SETTINGS) {
                    SettingsScreen(updates, metronome, onOpenCalibration = { screen = Screen.CALIBRATION },
                        onBack = { screen = Screen.MAIN })
                } else if (screen == Screen.CALIBRATION) {
                    CalibrationScreen(metronome, onBack = { screen = Screen.SETTINGS })
                } else when (tab) {
                    Tab.METRONOME -> MetronomeScreen(metronome, onTogglePlay = togglePlay,
                        onOpenSettings = { screen = Screen.SETTINGS }, onComingSoon = say)
                    Tab.SONGS -> EmptyState(
                        "Add your first song",
                        "Pick a song on your phone, share one from another app, or paste a YouTube, SoundCloud or Spotify link. You'll get a click track that follows its tempo.",
                        "Add a song",
                    ) { say("Song analysis is coming in a later build") }
                    Tab.PRACTICE -> EmptyState(
                        "Practice tools",
                        "Speed trainer, gap click and timing feedback will live here.",
                        null,
                    ) {}
                    Tab.SETLISTS -> EmptyState(
                        "Build a setlist",
                        "Save tempos and songs in the order you play them.",
                        "New setlist",
                    ) { say("Setlists are coming in a later build") }
                }
            }
        }

        if (flashAlpha.value > 0f) {
            val c = if (flashAccent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxSize().background(c.copy(alpha = flashAlpha.value)))
        }
        }

        UpdateDialog()
    }

    @Composable
    private fun UpdateDialog() {
        val show by updates.showDialog.collectAsStateWithLifecycle()
        val state by updates.state.collectAsStateWithLifecycle()
        val failure by updates.failures.collectAsStateWithLifecycle()
        if (!show) return

        when (val s = state) {
            is UpdateState.Available -> AlertDialog(
                onDismissRequest = updates::dismissDialog,
                title = { Text("Update available") },
                text = {
                    Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                        Text("${s.info.versionName} · you have ${BuildConfig.VERSION_NAME}")
                        if (s.info.notes.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(s.info.notes, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { updates.startUpdate(s.info) }) { Text("Update") } },
                dismissButton = { TextButton(onClick = updates::dismissDialog) { Text("Later") } },
            )
            is UpdateState.Downloading, UpdateState.Installing -> AlertDialog(
                onDismissRequest = {},
                title = { Text(if (s is UpdateState.Downloading) "Downloading update" else "Installing") },
                text = {
                    val p = (s as? UpdateState.Downloading)?.progress ?: -1f
                    if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                },
                confirmButton = {},
            )
            is UpdateState.Failed -> AlertDialog(
                onDismissRequest = updates::dismissDialog,
                title = { Text("Update didn't finish") },
                text = { Text(failure ?: s.message) },
                confirmButton = { TextButton(onClick = { updates.dismissDialog(); updates.check() }) { Text("Try again") } },
                dismissButton = { TextButton(onClick = updates::dismissDialog) { Text("Close") } },
            )
            else -> if (failure != null) AlertDialog(
                onDismissRequest = updates::dismissDialog,
                title = { Text("Update didn't finish") },
                text = { Text(failure!!) },
                confirmButton = { TextButton(onClick = updates::dismissDialog) { Text("Close") } },
            )
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String, action: String?, onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(body, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}
