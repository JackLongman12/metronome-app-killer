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
        store.addPreset(id, "Outro", seven.copy(bpm = 90))
        store.move(id, store.get(id)!!.items[1].id, -1)

        val again = SetlistStore(f).get(id)!!
        assertEquals("Friday gig", again.name)
        assertEquals(listOf("Outro", "Intro"), again.items.map { it.name })
        val preset = again.items[1] as SetItem.Preset
        assertEquals(seven.copy(playing = false), preset.settings)
        assertEquals("132 BPM · 7/8 · eighths", SetlistStore.describe(preset.settings))
        f.delete()
    }

    /** Setlists saved before songs were removed can still list songs: they're dropped, and the file is cleaned. */
    @Test fun dropsOldSongItems() {
        val f = File.createTempFile("setlists", ".json")
        f.writeText("""[{"id":"a","name":"Gig","items":[
            {"type":"song","id":"s1","name":"Racecar","song":"song-1"},
            {"type":"preset","id":"p1","name":"Intro","bpm":120,"num":4.0,"den":4.0,"beats":["ACCENT","NORMAL","NORMAL","NORMAL"],"sub":"QUARTERS","sound":"CLICK"}]}]""")
        val list = SetlistStore(f).get("a")!!
        assertEquals(listOf("Intro"), list.items.map { it.name })
        assertEquals(false, f.readText().contains("\"song\""))
        f.delete()
    }
}
