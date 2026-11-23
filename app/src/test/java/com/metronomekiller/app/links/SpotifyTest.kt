package com.metronomekiller.app.links

import com.metronomekiller.app.links.Spotify.Candidate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class SpotifyTest {
    @Test fun parsesLinks() {
        assertEquals(Spotify.Ref("track", "4cOdK2wGLETKBW3PvgPWqT"), Spotify.parse("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT?si=abc123"))
        assertEquals(Spotify.Ref("album", "4LH4d3cOWNNsVw41Gqt2kv"), Spotify.parse("https://open.spotify.com/intl-de/album/4LH4d3cOWNNsVw41Gqt2kv"))
        assertEquals(Spotify.Ref("playlist", "37i9dQZF1DXcBWIGoYBM5M"), Spotify.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(Spotify.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    }

    @Test fun readsEmbedEntities() {
        val track = Spotify.fromEntity(JSONObject("""{"type":"track","id":"T1","name":"Money","duration":382000,"artists":[{"name":"Pink Floyd"}]}"""))
        assertEquals(Links.Resolved.Single(Links.Item("https://open.spotify.com/track/T1", "Money", "Pink Floyd", 382)), track)
        val album = Spotify.fromEntity(JSONObject("""{"type":"playlist","name":"Mix","trackList":[
            {"uri":"spotify:track:A","title":"One","subtitle":"Artist X, Artist Y","duration":200500},
            {"uri":"spotify:episode:E","title":"A podcast","subtitle":"","duration":1}]}"""))
        album as Links.Resolved.Playlist
        assertEquals("Mix", album.name)
        assertEquals(listOf(Links.Item("https://open.spotify.com/track/A", "One", "Artist X, Artist Y", 200)), album.items)
    }

    @Test fun picksTheSameRecording() {
        val results = listOf(
            Candidate("live", "Pink Floyd - Money (Live at Pompeii)", "Pink Floyd", 382),
            Candidate("video", "Pink Floyd - Money (Official Music Video)", "Pink Floyd", 410),
            Candidate("topic", "Money", "Pink Floyd - Topic", 383),
            Candidate("other", "Money Trees", "Kendrick Lamar - Topic", 386),
        )
        assertEquals("topic", Spotify.pick(results, "Money", "Pink Floyd", 382)?.url)
        // Asked for the live version: now the live upload wins.
        assertEquals("live", Spotify.pick(results, "Money - Live at Pompeii", "Pink Floyd", 382)?.url)
        // Nothing near the right length: no guess.
        assertNull(Spotify.pick(results, "Money", "Pink Floyd", 200))
    }

    /** Real Spotify + YouTube: NETWORK_TESTS=1 ./gradlew testDebugUnitTest */
    @Test fun liveTrackFindsYoutube() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val r = Spotify.resolve("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT") as Links.Resolved.Single
        println("spotify: ${r.item}")
        assertEquals("Never Gonna Give You Up", r.item.title)
        val url = Spotify.matchOnYouTube(r.item.title, r.item.artist, r.item.durationSec)
        println("youtube: $url")
        assertNotNull(url)
        val album = Spotify.resolve("https://open.spotify.com/album/4LH4d3cOWNNsVw41Gqt2kv") as Links.Resolved.Playlist
        println("album: ${album.name}, ${album.items.size} songs, first ${album.items.first()}")
        assertEquals(10, album.items.size)
    }
}
