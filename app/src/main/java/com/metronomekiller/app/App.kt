package com.metronomekiller.app

import android.app.Application
import com.metronomekiller.app.engine.MetronomeController

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

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        metronome = MetronomeController(this, prefs)
    }

    companion object {
        fun from(context: android.content.Context) = context.applicationContext as App
    }
}
