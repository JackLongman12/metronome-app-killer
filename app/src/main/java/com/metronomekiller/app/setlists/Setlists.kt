package com.metronomekiller.app.setlists

import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.ClickSound
import com.metronomekiller.app.engine.Meter
import com.metronomekiller.app.engine.MetronomeState
import com.metronomekiller.app.engine.Subdivision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** One entry in a setlist: saved metronome settings ("Intro click, 132 BPM, 7/8"). */
sealed interface SetItem {
    val id: String
    val name: String
    val settings: MetronomeState

    data class Preset(override val id: String, override val name: String, override val settings: MetronomeState) : SetItem
}

data class Setlist(val id: String, val name: String, val items: List<SetItem>)

/**
 * Your setlists, saved as JSON in the app's private storage (files/setlists.json).
 *
 * LEARN: [active] remembers which setlist you're playing through and where you
 * are in it, so the Metronome tab can show "3 of 12 · Next" for quick changes
 * between songs on stage.
 */
class SetlistStore(private val file: File) {
    private val _lists = MutableStateFlow(load())
    val lists: StateFlow<List<Setlist>> = _lists

    /** The setlist being played through and the current item's index, or null. */
    private val _active = MutableStateFlow<Pair<String, Int>?>(null)
    val active: StateFlow<Pair<String, Int>?> = _active

    fun get(id: String) = _lists.value.firstOrNull { it.id == id }

    fun create(name: String, items: List<SetItem> = emptyList()): String {
        val s = Setlist(UUID.randomUUID().toString(), name.ifBlank { "New setlist" }, items)
        change { it + s }
        return s.id
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.ifBlank { it.name }) }

    fun delete(id: String) {
        change { list -> list.filterNot { it.id == id } }
        if (_active.value?.first == id) _active.value = null
    }

    fun addPreset(id: String, name: String, settings: MetronomeState) =
        edit(id) { it.copy(items = it.items + SetItem.Preset(UUID.randomUUID().toString(), name, settings.copy(playing = false))) }

    /** Replace a preset's settings (keeps its name and place). */
    fun updatePreset(id: String, itemId: String, settings: MetronomeState) = edit(id) { s ->
        s.copy(items = s.items.map { if (it is SetItem.Preset && it.id == itemId) it.copy(settings = settings.copy(playing = false)) else it })
    }

    fun renameItem(id: String, itemId: String, name: String) = edit(id) { s ->
        s.copy(items = s.items.map {
            if (it.id != itemId || name.isBlank()) it
            else when (it) { is SetItem.Preset -> it.copy(name = name) }
        })
    }

    fun removeItem(id: String, itemId: String) = edit(id) { s -> s.copy(items = s.items.filterNot { it.id == itemId }) }

    /** Move an item up (-1) or down (+1). */
    fun move(id: String, itemId: String, by: Int) = edit(id) { s ->
        val i = s.items.indexOfFirst { it.id == itemId }
        val j = i + by
        if (i < 0 || j !in s.items.indices) s
        else s.copy(items = s.items.toMutableList().also { val t = it[i]; it[i] = it[j]; it[j] = t })
    }

    fun setActive(id: String?, index: Int = 0) { _active.value = id?.let { it to index } }

    private fun edit(id: String, f: (Setlist) -> Setlist) = change { list -> list.map { if (it.id == id) f(it) else it } }

    private fun change(f: (List<Setlist>) -> List<Setlist>) {
        _lists.update(f)
        runCatching { file.writeText(toJson(_lists.value).toString()) }
    }

    private fun load(): List<Setlist> = runCatching {
        val text = file.readText()
        val lists = fromJson(JSONArray(text))
        // Songs were removed from the app (build 28): setlists saved before then may
        // still list songs. They were dropped while loading; save so they're gone for good.
        if (text.contains("\"type\":\"song\"")) file.writeText(toJson(lists).toString())
        lists
    }.getOrDefault(emptyList())

    companion object {
        fun toJson(lists: List<Setlist>) = JSONArray(lists.map { s ->
            JSONObject().put("id", s.id).put("name", s.name).put("items", JSONArray(s.items.map { item ->
                when (item) {
                    is SetItem.Preset -> JSONObject().put("type", "preset").put("id", item.id).put("name", item.name)
                        .put("bpm", item.settings.bpm)
                        .put("num", item.settings.meter.numerator).put("den", item.settings.meter.denominator)
                        .put("beats", JSONArray(item.settings.beats.map { it.name }))
                        .put("sub", item.settings.subdivision.name).put("sound", item.settings.sound.name)
                }
            }))
        })

        fun fromJson(a: JSONArray): List<Setlist> = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val items = o.getJSONArray("items")
            Setlist(o.getString("id"), o.getString("name"), (0 until items.length()).map { items.getJSONObject(it) }
                .filter { it.getString("type") != "song" }.map {
                run {
                    val beats = it.getJSONArray("beats").let { b -> List(b.length()) { n -> BeatState.valueOf(b.getString(n)) } }
                    SetItem.Preset(it.getString("id"), it.getString("name"), MetronomeState(
                        bpm = it.getInt("bpm"),
                        meter = Meter(it.getDouble("num"), it.getDouble("den")),
                        beats = beats,
                        subdivision = Subdivision.valueOf(it.getString("sub")),
                        sound = ClickSound.valueOf(it.getString("sound")),
                    ))
                }
            })
        }

        /** Short description of a metronome preset, e.g. "132 BPM · 7/8". */
        fun describe(m: MetronomeState) = "${m.bpm} BPM · ${m.meter.label}" +
            if (m.subdivision != Subdivision.QUARTERS) " · ${m.subdivision.label.lowercase()}" else ""
    }
}
