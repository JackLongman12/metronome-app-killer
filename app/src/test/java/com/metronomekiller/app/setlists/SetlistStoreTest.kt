package com.metronomekiller.app.setlists

import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.ClickSound
import com.metronomekiller.app.engine.Meter
import com.metronomekiller.app.engine.MetronomeState
import com.metronomekiller.app.engine.Subdivision
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SetlistStoreTest {
    @Test fun savesReordersAndReloads() {
        val f = File.createTempFile("setlists", ".json").apply { delete() }
        val store = SetlistStore(f)
        val id = store.create("Friday gig")
        val seven = MetronomeState(bpm = 132, meter = Meter(7.0, 8.0),
            beats = listOf(BeatState.ACCENT, BeatState.NORMAL, BeatState.ACCENT, BeatState.NORMAL, BeatState.STRONG, BeatState.NORMAL, BeatState.MUTE),
            subdivision = Subdivision.EIGHTHS, sound = ClickSound.CLAVE, playing = true)
        store.addPreset(id, "Intro", seven)
        store.addSong(id, "song-1", "Racecar")
        store.move(id, store.get(id)!!.items[1].id, -1)

        val again = SetlistStore(f).get(id)!!
        assertEquals("Friday gig", again.name)
        assertEquals(listOf("Racecar", "Intro"), again.items.map { it.name })
        val preset = again.items[1] as SetItem.Preset
        assertEquals(seven.copy(playing = false), preset.settings)
        assertEquals("132 BPM · 7/8 · eighths", SetlistStore.describe(preset.settings))
        f.delete()
    }
}
