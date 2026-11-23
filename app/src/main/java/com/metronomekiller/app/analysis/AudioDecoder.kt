package com.metronomekiller.app.analysis

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can play (mp3, m4a/aac, ogg, flac, wav...)
 * into MONO samples at 22050 Hz, the format the beat model expects.
 *
 * LEARN — how decoding works on Android:
 * - MediaExtractor reads the file and hands out compressed "samples" (packets).
 * - MediaCodec is the hardware/software decoder: we feed it packets through
 *   input buffers and collect raw PCM audio from output buffers.
 * - PCM comes interleaved (L, R, L, R...), usually as 16-bit integers.
 * We average the channels to mono and stream it straight into the resampler,
 * so even a 15-minute song never sits in memory at full quality.
 */
object AudioDecoder {
    const val TARGET_RATE = 22050

    class Decoded(val samples: FloatArray, val durationSec: Double)

    fun decodeToMono22k(context: Context, uri: Uri, onProgress: (Float) -> Unit): Decoded {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track in this file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        // LEARN — encoder delay: MP3/AAC files start with a few hundred to ~2000
        // samples of padding the encoder needs. Players skip it ("gapless"
        // playback); if analysis didn't, every beat would come out ~25-50 ms
        // late against what you hear. The file tells us how much to skip.
        var skipFrames = if (format.containsKey("encoder-delay")) format.getInteger("encoder-delay") else 0

        var resampler: StreamResampler? = null
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatPcm = false
        val out = GrowableFloats(TARGET_RATE * 240)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var mono = FloatArray(0)

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        resampler = StreamResampler(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), TARGET_RATE)
                    }
                    outIndex >= 0 -> {
                        val buf = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val frames = if (floatPcm) info.size / 4 / channels else info.size / 2 / channels
                        if (mono.size < frames) mono = FloatArray(frames)
                        if (floatPcm) {
                            val fb = buf.asFloatBuffer()
                            for (i in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get()
                                mono[i] = s / channels
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            for (i in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += sb.get() / 32768f
                                mono[i] = s / channels
                            }
                        }
                        var start = 0
                        if (skipFrames > 0) {
                            start = minOf(skipFrames, frames)
                            skipFrames -= start
                        }
                        if (start > 0) System.arraycopy(mono, start, mono, 0, frames - start)
                        val usable = frames - start
                        val rs = resampler ?: StreamResampler(format.getInteger(MediaFormat.KEY_SAMPLE_RATE), TARGET_RATE)
                            .also { resampler = it }
                        rs.process(mono, usable, out)
                        if (durationUs > 0) onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            resampler?.flush(out)
        } finally {
            codec.stop()
            codec.release()
            extractor.release()
        }
        val samples = out.toArray()
        return Decoded(samples, samples.size / TARGET_RATE.toDouble())
    }
}

/** A FloatArray that grows as you append (like ArrayList, without boxing every float). */
class GrowableFloats(initial: Int) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf((data.size * 3 / 2) + 1024)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
