package com.metronomekiller.app

import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * The Songs feature was removed (build 28). This deletes everything it left on
 * the phone, once, the first time the new version starts:
 *  - downloaded songs and their tempo maps (files/songs)
 *  - the beat-tracking models (files/models, up to ~100 MB)
 *  - half-finished downloads in the cache
 *  - its settings, and access to audio files you picked from your phone
 *    (your own files stay where they are; the app just stops being allowed to read them)
 *
 * LEARN: "filesDir" is the app's private folder. Nothing else can see it, and
 * Android deletes it when you uninstall, but an update keeps it. That's why an
 * update has to clean up after a removed feature itself.
 */
object SongsRemoval {
    private const val DONE = "songs_removed"

    fun run(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean(DONE, false)) return
        File(context.filesDir, "songs").deleteRecursively()
        File(context.filesDir, "models").deleteRecursively()
        context.cacheDir.listFiles()?.forEach { f ->
            // keep the app updater's folder; everything else in the cache was song downloads
            if (f.name != "updates") f.deleteRecursively()
        }
        runCatching {
            context.contentResolver.persistedUriPermissions.forEach {
                context.contentResolver.releasePersistableUriPermission(it.uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        prefs.edit {
            listOf("song_level", "song_click_level", "songsterr", "accurate_model", "part_rows").forEach { remove(it) }
            putBoolean(DONE, true)
        }
    }
}
