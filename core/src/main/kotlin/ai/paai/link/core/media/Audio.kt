package ai.paai.link.core.media

import ai.paai.link.core.protocol.CodecConfig
import java.io.Closeable

/*
 * Platform audio abstractions. Android implements them with AudioRecord / AudioTrack and the
 * native libopus binding; tests use in-memory fakes. All calls are blocking and are only ever
 * made from the session's dedicated audio threads, never from the main thread.
 */

interface AudioCapture : Closeable {
    fun start()
    /** Blocks until one frame of mono 16-bit PCM is read. Returns samples read, or <= 0 on error/stop. */
    fun read(frame: ShortArray): Int
    fun stop()
}

interface AudioPlayback : Closeable {
    fun start()
    /** Blocks until the frame is queued; pacing the playout loop. */
    fun write(frame: ShortArray, samples: Int): Int
    fun stop()
}

interface AudioEncoder : Closeable {
    /** Returns encoded length, or < 0 on error. */
    fun encode(pcm: ShortArray, out: ByteArray): Int
    fun setBitrate(bitsPerSecond: Int)
}

interface AudioDecoder : Closeable {
    /** [packet] == null asks the codec to conceal a lost frame. Returns decoded samples, or < 0 on error. */
    fun decode(packet: ByteArray?, out: ShortArray, fec: Boolean = false): Int
}

interface AudioEngine {
    fun capture(codec: CodecConfig): AudioCapture
    fun playback(codec: CodecConfig): AudioPlayback
    fun encoder(codec: CodecConfig): AudioEncoder
    fun decoder(codec: CodecConfig): AudioDecoder
    /** Called first on each audio thread (Android raises the thread to audio priority). */
    fun onAudioThreadStart() {}
}

object Pcm {
    /** RMS level of a frame in 0..1. */
    fun level(frame: ShortArray, n: Int = frame.size): Float {
        if (n <= 0) return 0f
        var sum = 0.0
        for (i in 0 until n) { val s = frame[i].toDouble(); sum += s * s }
        return (kotlin.math.sqrt(sum / n) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    /** Adds [src] into the 32-bit accumulator. */
    fun accumulate(acc: IntArray, src: ShortArray, n: Int) {
        for (i in 0 until minOf(n, acc.size)) acc[i] += src[i].toInt()
    }

    fun clampInto(acc: IntArray, out: ShortArray) {
        for (i in acc.indices) out[i] = acc[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
