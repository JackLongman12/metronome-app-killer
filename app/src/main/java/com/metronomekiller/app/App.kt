package com.metronomekiller.app

import android.app.Application
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.songs.SongLibrary

/**
 * The Application object: created once, before any screen, and lives as long as
 * the app process does.
 *
 * LEARN: this is a simple form of "dependency injection" — one place builds the
 * long-lived objects, and everything else gets them from here via `App.from(context)`.
 * It's registered in AndroidManifest.xml with android:name=".App".
 */
class App : Application() {
    lateinit var prefs: AppPrefs
        private set
    lateinit var metronome: MetronomeController
        private set
    lateinit var songs: SongLibrary
        private set
    lateinit var practice: com.metronomekiller.app.practice.PracticeController
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        prefs = AppPrefs(this)
        metronome = MetronomeController(this, prefs)
        songs = SongLibrary(this)
        practice = com.metronomekiller.app.practice.PracticeController(this, metronome)
    }

    companion object {
        fun from(context: android.content.Context) = context.applicationContext as App
    }
}
