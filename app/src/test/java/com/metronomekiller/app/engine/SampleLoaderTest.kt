package com.metronomekiller.app.engine

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SampleLoaderTest {
    /** Every shipped sound decodes, starts on its hit, and is short enough for fast tempos. */
    @Test fun shippedSoundsDecode() {
        val dir = listOf(File("src/main/assets/sounds"), File("app/src/main/assets/sounds")).first { it.isDirectory }
        val files = dir.listFiles { f -> f.extension == "wav" }!!
        assertEquals(29, files.size)
        files.forEach { f ->
            val (samples, rate) = SampleLoader.decodeWav(f.readBytes())
            assertEquals(44100, rate)
            assert(samples.size in 2000..20000) { "${f.name}: ${samples.size} samples" }
            val peak = samples.maxOf { kotlin.math.abs(it) }
            // The hit begins within the first 1 ms (prepare_samples.py cuts the lead-in).
            assert(samples.take(44).any { kotlin.math.abs(it) > peak * 0.02f }) { "${f.name} starts late" }
        }
    }

    @Test fun skipsUnknownChunks() {
        // RIFF header, a "LIST" chunk to skip (odd length → 1 pad byte), fmt, then 2 samples.
        val out = java.io.ByteArrayOutputStream()
        fun le(v: Int, n: Int) { for (i in 0 until n) out.write((v shr (8 * i)) and 0xff) }
        out.write("RIFF".toByteArray()); le(0, 4); out.write("WAVE".toByteArray())
        out.write("LIST".toByteArray()); le(3, 4); out.write(byteArrayOf(1, 2, 3, 0))
        out.write("fmt ".toByteArray()); le(16, 4); le(1, 2); le(1, 2); le(48000, 4); le(96000, 4); le(2, 2); le(16, 2)
        out.write("data".toByteArray()); le(4, 4); le(16384, 2); le(-32768 and 0xffff, 2)
        val (s, rate) = SampleLoader.decodeWav(out.toByteArray())
        assertEquals(48000, rate)
        assertEquals(listOf(0.5f, -1f), s.toList())
    }
}
