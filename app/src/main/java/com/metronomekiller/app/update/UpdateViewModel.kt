package com.metronomekiller.app.update

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metronomekiller.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: ReleaseInfo) : UpdateState
    data class NeedsPermission(val info: ReleaseInfo) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data object Installing : UpdateState
    data class Failed(val message: String) : UpdateState
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val updateDir = File(app.cacheDir, "updates")

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Whether the "update available" dialog should be shown (auto-check result). */
    private val _showDialog = MutableStateFlow(false)
    val showDialog: StateFlow<Boolean> = _showDialog

    private val _autoCheck = MutableStateFlow(prefs.getBoolean("auto_check", true))
    val autoCheck: StateFlow<Boolean> = _autoCheck

    val failures = InstallResultReceiver.failures

    init {
        updateDir.deleteRecursively() // leftovers from a finished update
        if (_autoCheck.value) check(silent = true)
    }

    fun setAutoCheck(enabled: Boolean) {
        _autoCheck.value = enabled
        prefs.edit { putBoolean("auto_check", enabled) }
    }

    fun check(silent: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            _state.value = try {
                val latest = UpdateChecker.fetchLatest()
                if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
                    _showDialog.value = true
                    UpdateState.Available(latest)
                } else UpdateState.UpToDate
            } catch (e: Exception) {
                if (silent) UpdateState.Idle else UpdateState.Failed(e.message ?: "Couldn't reach GitHub")
            }
        }
    }

    fun dismissDialog() { _showDialog.value = false }

    fun startUpdate(info: ReleaseInfo) {
        val ctx = getApplication<Application>()
        if (!UpdateInstaller.canInstall(ctx)) {
            _state.value = UpdateState.NeedsPermission(info)
            UpdateInstaller.openInstallPermissionSettings(ctx)
            return
        }
        _showDialog.value = true
        InstallResultReceiver.clearFailure()
        viewModelScope.launch {
            try {
                val apk = File(updateDir, "build-${info.versionCode}.apk")
                _state.value = UpdateState.Downloading(info, 0f)
                UpdateChecker.download(info, apk) { p -> _state.value = UpdateState.Downloading(info, p) }
                _state.value = UpdateState.Installing
                UpdateInstaller.install(ctx, apk)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Update failed")
            }
        }
    }

    /** Called when returning from the "install unknown apps" settings screen. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission && UpdateInstaller.canInstall(getApplication())) {
            startUpdate(s.info)
        }
    }
}
