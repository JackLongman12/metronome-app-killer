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
import androidx.activity.SystemBarStyle
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.metronome.MetronomeScreen
import com.metronomekiller.app.settings.CalibrationScreen
import com.metronomekiller.app.settings.SettingsScreen
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.ui.AppTheme
import com.metronomekiller.app.songs.SongScreen
import com.metronomekiller.app.songs.SongsScreen
import com.metronomekiller.app.ui.isComic
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import com.metronomekiller.app.update.UpdateState
import com.metronomekiller.app.update.UpdateViewModel
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector) {
    METRONOME("Metronome", AppIcons.Metronome),
    SONGS("Songs", AppIcons.Music),
    PRACTICE("Practice", AppIcons.Target),
    SETLISTS("Setlists", AppIcons.List),
    SETTINGS("Settings", AppIcons.Tune),
}

private enum class Screen { MAIN, CALIBRATION }

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

    /** Set when a link is shared to the app, so the UI can jump to the Songs tab. */
    private val sharedLink = androidx.compose.runtime.mutableStateOf<String?>(null)

    /**
     * LEARN: when you tap Share → Metronome Killer in the YouTube app, Android starts
     * (or brings back) this activity with an ACTION_SEND intent carrying the link
     * as text. onCreate handles a cold start, onNewIntent a running app.
     */
    private fun handleShare(intent: android.content.Intent?) {
        if (intent?.action != android.content.Intent.ACTION_SEND) return
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: return
        val url = com.metronomekiller.app.links.Links.findUrl(text) ?: return
        App.from(this).songs.addLink(url)
        sharedLink.value = url
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        handleShare(intent)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Make the phone's volume buttons control media (our click) volume,
        // even when the metronome isn't playing yet.
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        setContent {
            val colors by App.from(this).prefs.colors.collectAsStateWithLifecycle()
            val dark = colors.isDark()
            // Status-bar icons must be light on dark schemes and dark on light ones.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            AppTheme(colors) { App() }
        }
    }

    override fun onStart() {
        super.onStart()
        metronome.setForeground(true)
    }

    override fun onStop() {
        super.onStop()
        metronome.setForeground(false)
    }

    override fun onResume() {
        super.onResume()
        updates.onResume()
    }

    @Composable
    private fun App() {
        var tab by rememberSaveable { mutableStateOf(Tab.METRONOME) }
        // A link shared from YouTube: show it arriving in Songs.
        val shared by sharedLink
        LaunchedEffect(shared) { if (shared != null) { tab = Tab.SONGS; sharedLink.value = null } }
        var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
        var openSong by rememberSaveable { mutableStateOf<String?>(null) } // id of the song being viewed
        val prefs = metronome.prefs

        // Android 13+ needs permission to show the "metronome is playing" notification.
        // We ask once, the first time you press play. Playback works either way.
        val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        val askNotifications = {
            if (Build.VERSION.SDK_INT >= 33 && !prefs.askedNotifications &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                prefs.askedNotifications = true
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val togglePlay = {
            askNotifications()
            metronome.togglePlay()
        }
        // If the app crashed last time, show what happened so it can be copied into a bug report.
        var crash by remember { mutableStateOf(CrashLog.read(this)) }
        crash?.let { report ->
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text("The app crashed last time") },
                text = {
                    Column {
                        Text("Copy this and send it to whoever builds the app, so the cause can be fixed.",
                            style = MaterialTheme.typography.bodySmall)
                        Text(report, fontSize = 10.sp, lineHeight = 12.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            modifier = Modifier.padding(top = 8.dp).height(260.dp)
                                .verticalScroll(androidx.compose.foundation.rememberScrollState()))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(report))
                    }) { Text("Copy") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { CrashLog.clear(this@MainActivity); crash = null }) { Text("Close") }
                },
            )
        }
        val snackbar = remember { SnackbarHostState() }
        val setlists = App.from(this).setlists
        val scope = rememberCoroutineScope()
        val say: (String) -> Unit = { msg -> scope.launch { snackbar.showSnackbar(msg) } }

        // Keep the screen on while the click is running.
        val playing = metronome.state.collectAsStateWithLifecycle().value.playing
        LaunchedEffect(playing) {
            if (playing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        BackHandler(enabled = screen != Screen.MAIN) {
            screen = Screen.MAIN
        }
        BackHandler(enabled = screen == Screen.MAIN && tab == Tab.SONGS && openSong != null) { openSong = null }

        // Full-screen flash on each beat (optional). A flat tint that fades fast — no glow.
        val flashOn by prefs.flash.collectAsStateWithLifecycle()
        val flashAlpha = remember { Animatable(0f) }
        var flashAccent by remember { mutableStateOf(false) }
        LaunchedEffect(flashOn) {
            if (!flashOn) return@LaunchedEffect
            metronome.beatEvents.collect { e ->
                if (e.state == BeatState.MUTE) return@collect
                if (prefs.flashAccentsOnly.value && !e.state.isAccent) return@collect
                flashAccent = e.state.isAccent
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
                if (screen == Screen.MAIN && isComic()) ComicTabs(tab) { tab = it }
                else if (screen == Screen.MAIN) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
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
                // LEARN: playing an item of a setlist loads it where it belongs: saved
                // metronome settings go to the Metronome tab, songs open on the Songs tab.
                val playSetItem: (String, Int) -> Unit = { listId, index ->
                    setlists.get(listId)?.items?.getOrNull(index)?.let { item ->
                        setlists.setActive(listId, index)
                        when (item) {
                            is com.metronomekiller.app.setlists.SetItem.Preset -> {
                                metronome.restore(item.settings); openSong = null; tab = Tab.METRONOME
                            }
                            is com.metronomekiller.app.setlists.SetItem.Song -> {
                                val s = App.from(this@MainActivity).songs.song(item.songId)
                                if (s?.map != null) { metronome.stop(); openSong = s.id; tab = Tab.SONGS }
                                else say(if (s == null) "That song was removed from your library" else "That song is still being analysed")
                            }
                        }
                    }
                }
                val active by setlists.active.collectAsStateWithLifecycle()
                val lists by setlists.lists.collectAsStateWithLifecycle()
                val activeList = active?.let { (id, _) -> lists.firstOrNull { it.id == id } }
                if (activeList != null && screen == Screen.MAIN && (tab == Tab.METRONOME || tab == Tab.SONGS)) {
                    com.metronomekiller.app.setlists.SetlistStrip(
                        activeList, active!!.second,
                        onPrev = { playSetItem(activeList.id, active!!.second - 1) },
                        onNext = { playSetItem(activeList.id, active!!.second + 1) },
                        onClose = { setlists.setActive(null) },
                    )
                }
                if (screen == Screen.CALIBRATION) {
                    CalibrationScreen(metronome, onBack = { screen = Screen.MAIN })
                } else when (tab) {
                    Tab.SETTINGS -> SettingsScreen(updates, metronome,
                        onOpenCalibration = { screen = Screen.CALIBRATION }, onBack = null)
                    Tab.METRONOME -> MetronomeScreen(metronome, onTogglePlay = togglePlay,
                        onLoadSong = { tab = Tab.SONGS }, onComingSoon = say)
                    Tab.SONGS -> {
                        val library = App.from(this@MainActivity).songs
                        // LEARN: collect the list as state, so the song screen redraws whenever the
                        // song changes (an edit, a rename, switching maps). Reading library.song(id)
                        // once gave the screen a frozen copy, and edits looked like they did nothing.
                        val songs by library.songs.collectAsStateWithLifecycle()
                        val song = openSong?.let { id -> songs.firstOrNull { it.id == id } }
                        if (song?.map != null) SongScreen(song, metronome, library, onBack = { openSong = null })
                        else SongsScreen(library, onOpen = { openSong = it })
                    }
                    Tab.PRACTICE -> com.metronomekiller.app.practice.PracticeScreen(
                        App.from(this@MainActivity).practice, metronome, onPlay = askNotifications,
                    )
                    Tab.SETLISTS -> com.metronomekiller.app.setlists.SetlistsScreen(
                        setlists, App.from(this@MainActivity).songs, metronome, onPlay = playSetItem,
                    )
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

/** Comic-theme bottom bar: ruled cells, the selected tab inked solid black. */
@Composable
private fun ComicTabs(selected: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(2.5.dp).background(Color.Black))
        Row(Modifier.fillMaxWidth().height(52.dp)) {
            Tab.entries.forEachIndexed { i, t ->
                if (i > 0) Box(Modifier.width(2.dp).fillMaxHeight().background(Color.Black))
                val on = t == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight().background(if (on) Color.Black else Color.White).clickable { onSelect(t) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(t.label.uppercase(), style = MaterialTheme.typography.titleSmall, color = if (on) Color.White else Color.Black, maxLines = 1)
                }
            }
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
