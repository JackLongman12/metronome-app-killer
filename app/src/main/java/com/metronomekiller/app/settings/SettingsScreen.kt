package com.metronomekiller.app.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.BuildConfig
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.update.UpdateState
import com.metronomekiller.app.update.UpdateViewModel

/**
 * Settings: beat feedback (flash/vibration), audio sync, updates, about.
 *
 * LEARN: the screen holds no settings itself — it reads StateFlows from
 * AppPrefs / UpdateViewModel and calls their setters. That keeps one source
 * of truth, so the metronome reacts instantly to a toggle here.
 */
@Composable
fun SettingsScreen(
    updates: UpdateViewModel,
    controller: MetronomeController,
    onOpenCalibration: () -> Unit,
    onBack: () -> Unit,
) {
    val state by updates.state.collectAsStateWithLifecycle()
    val auto by updates.autoCheck.collectAsStateWithLifecycle()
    val flash by controller.prefs.flash.collectAsStateWithLifecycle()
    val vibrate by controller.prefs.vibrate.collectAsStateWithLifecycle()
    val route by controller.route.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(AppIcons.Back, "Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        SectionLabel("Beat feedback")
        Toggle("Flash the screen", "Useful on loud stages or with earplugs", flash, controller.prefs::setFlash)
        Toggle("Vibrate", "Feel the beat, accents are stronger", vibrate, controller.prefs::setVibrate)
        Item("Audio sync", "${route.label} · ${controller.prefs.latencyMs(route.key)} ms. Line up visuals with Bluetooth delay", onOpenCalibration)

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("Updates")
        Item(
            title = "Check for updates",
            subtitle = when (val s = state) {
                UpdateState.Idle -> "Version ${BuildConfig.VERSION_NAME}"
                UpdateState.Checking -> "Checking…"
                UpdateState.UpToDate -> "You're on the latest build (${BuildConfig.VERSION_NAME})"
                is UpdateState.Available -> "${s.info.versionName} is available"
                is UpdateState.NeedsPermission -> "Allow installs from this app, then come back"
                is UpdateState.Downloading -> "Downloading…"
                UpdateState.Installing -> "Installing…"
                is UpdateState.Failed -> s.message
            },
            onClick = {
                when (val s = state) {
                    is UpdateState.Available -> updates.startUpdate(s.info)
                    is UpdateState.NeedsPermission -> updates.startUpdate(s.info)
                    else -> updates.check()
                }
            },
        )
        Toggle("Check when the app opens", "New builds from GitHub install in one tap", auto, updates::setAutoCheck)

        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        SectionLabel("About")
        Item("Source code", "github.com/${BuildConfig.GITHUB_REPO}") {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.GITHUB_REPO}")))
        }
        Item("Version", "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})") {}
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Item(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(title)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
