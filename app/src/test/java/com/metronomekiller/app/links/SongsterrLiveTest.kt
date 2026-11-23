package com.metronomekiller.app.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Talks to the real Songsterr API, so it only runs when asked:
 *   NETWORK_TESTS=1 ./gradlew testDebugUnitTest
 * Checks that a messy YouTube title finds the right tab and that the synced
 * tempo map has every bar and the song's meter changes.
 */
class SongsterrLiveTest {
    @Test
    fun racecarFromYoutubeTitle() {
        assumeTrue(System.getenv("NETWORK_TESTS") == "1")
        val tab = Songsterr.find("Periphery - Racecar (HQ Audio)", "ILikeEatingGuitars")
        assertNotNull("tab found", tab)
        tab!!
        println("found: ${tab.artist} - ${tab.title}, ${tab.bars.size} bars, videos ${tab.videos.keys}")
        assertEquals(617, tab.bars.size)
        val points = tab.videos["gGyPwkfmk4g"]
        assertNotNull("synced to the user's test video", points)
        val map = Songsterr.mapFromSync(tab, points!!, 920.8)
        val meters = map.bars.map { it.meter }.distinct()
        println("sections ${map.sections.size}, meters $meters, main ${map.mainBpm}, first ${map.sections.take(4)}")
        assertEquals(617, map.bars.size)
        assert("7/8" in meters && "4/4" in meters && "3/4" in meters)
        assert(map.sections.size in 20..60) { "usable number of parts: ${map.sections.size}" }
        assert(map.sections.filter { it.kind.name == "STEADY" }.all { kotlin.math.abs(it.bpm / (it.noteValue / 4.0) - 145) < 8 }) { "tempo ~145 quarter BPM" }
    }

    @Test
    fun cleansTitles() {
        assertEquals("Periphery Racecar", Songsterr.cleanQuery("Periphery - Racecar (HQ Audio)", "ILikeEatingGuitars").replace(" - ", " "))
    }
}
