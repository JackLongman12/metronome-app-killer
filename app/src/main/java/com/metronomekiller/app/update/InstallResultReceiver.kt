package com.metronomekiller.app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * LEARN: a BroadcastReceiver is code Android calls when something happens
 * (here: "your install finished / needs the user's OK"). It's declared in
 * AndroidManifest.xml. It must be quick — it just reacts and returns.
 */
/** Receives PackageInstaller session results and relays failures to the UI. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the process is replaced by the new version
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _failures.value = "Install failed ($status)" + (msg?.let { ": $it" } ?: "")
            }
        }
    }

    companion object {
        private val _failures = MutableStateFlow<String?>(null)
        val failures: StateFlow<String?> = _failures
        fun clearFailure() { _failures.value = null }
    }
}
