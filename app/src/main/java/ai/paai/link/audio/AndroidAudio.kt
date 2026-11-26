package ai.paai.link.audio

import ai.paai.link.core.media.AudioCapture
import ai.paai.link.core.media.AudioDecoder
import ai.paai.link.core.media.AudioEncoder
import ai.paai.link.core.media.AudioEngine
import ai.paai.link.core.media.AudioPlayback
import ai.paai.link.core.protocol.CodecConfig
import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process

/** JNI entry points into libopus (see src/main/cpp/opus_jni.c). */
object OpusNative {
    init {
        System.loadLibrary("paai_opus")
    }

    @JvmStatic external fun encoderCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    @JvmStatic external fun encoderCreateMusic(sampleRate: Int, channels: Int, bitrate: Int): Long
    @JvmStatic external fun encoderSetBitrate(handle: Long, bitrate: Int): Int
    @JvmStatic external fun encode(handle: Long, pcm: ShortArray, frameSize: Int, out: ByteArray): Int
    @JvmStatic external fun encoderDestroy(handle: Long)
    @JvmStatic external fun decoderCreate(sampleRate: Int, channels: Int): Long
    @JvmStatic external fun decode(handle: Long, packet: ByteArray?, packetLen: Int, pcm: ShortArray, frameSize: Int, fec: Int): Int
    @JvmStatic external fun decoderDestroy(handle: Long)
    @JvmStatic external fun version(): String
}

class OpusEncoder(private val codec: CodecConfig, music: Boolean = false) : AudioEncoder {
    private var handle = if (music) OpusNative.encoderCreateMusic(codec.sampleRate, 1, codec.bitrate) else OpusNative.encoderCreate(codec.sampleRate, 1, codec.bitrate)

    init {
        check(handle != 0L) { "Opus encoder could not be created" }
    }

    @Synchronized
    override fun encode(pcm: ShortArray, out: ByteArray): Int =
        if (handle == 0L) -1 else OpusNative.encode(handle, pcm, codec.frameSamples, out)

    @Synchronized
    override fun setBitrate(bitsPerSecond: Int) {
        if (handle != 0L) OpusNative.encoderSetBitrate(handle, bitsPerSecond)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) OpusNative.encoderDestroy(handle)
        handle = 0
    }
}

class OpusDecoder(private val codec: CodecConfig) : AudioDecoder {
    private var handle = OpusNative.decoderCreate(codec.sampleRate, 1)

    init {
        check(handle != 0L) { "Opus decoder could not be created" }
    }

    @Synchronized
    override fun decode(packet: ByteArray?, out: ShortArray, fec: Boolean): Int =
        if (handle == 0L) -1 else OpusNative.decode(handle, packet, packet?.size ?: 0, out, codec.frameSamples, if (fec) 1 else 0)

    @Synchronized
    override fun close() {
        if (handle != 0L) OpusNative.decoderDestroy(handle)
        handle = 0
    }
}

/**
 * Microphone via AudioRecord with the VOICE_COMMUNICATION source, which enables the platform's
 * voice-call tuning. Echo cancellation, noise suppression and gain control are attached when the
 * device offers them.
 */
@SuppressLint("MissingPermission") // MediaHooks refuses to start a session without RECORD_AUDIO.
class AndroidCapture(private val codec: CodecConfig) : AudioCapture {
    private val frameBytes = codec.frameSamples * 2
    private val record: AudioRecord
    private val effects = ArrayList<android.media.audiofx.AudioEffect>()

    init {
        val min = AudioRecord.getMinBufferSize(codec.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        require(min > 0) { "microphone does not support ${codec.sampleRate} Hz" }
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, codec.sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frameBytes * 4),
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("microphone unavailable")
        }
        val sid = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sid)?.let { it.enabled = true; effects += it }
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sid)?.let { it.enabled = true; effects += it }
        if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sid)?.let { it.enabled = true; effects += it }
    }

    override fun start() = record.startRecording()

    override fun read(frame: ShortArray): Int {
        var got = 0
        while (got < frame.size) {
            val n = record.read(frame, got, frame.size - got, AudioRecord.READ_BLOCKING)
            if (n <= 0) return n
            got += n
        }
        return got
    }

    override fun stop() {
        runCatching { record.stop() }
    }

    override fun close() {
        effects.forEach { runCatching { it.release() } }
        record.release()
    }
}

/** [music]: DJ Mode on the media stream (media volume, music quality); otherwise the call stream. */
class AndroidPlayback(private val codec: CodecConfig, private val music: Boolean = false) : AudioPlayback {
    private val track: AudioTrack

    init {
        val min = AudioTrack.getMinBufferSize(codec.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (music) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(if (music) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(codec.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min, codec.frameSamples * 2 * (if (music) 6 else 3)))
            .setPerformanceMode(if (music) AudioTrack.PERFORMANCE_MODE_NONE else AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    override fun start() = track.play()

    override fun write(frame: ShortArray, samples: Int): Int = track.write(frame, 0, samples, AudioTrack.WRITE_BLOCKING)

    override fun stop() {
        runCatching { track.pause(); track.flush() }
    }

    override fun close() = track.release()
}

class AndroidAudioEngine : AudioEngine {
    override fun capture(codec: CodecConfig): AudioCapture = AndroidCapture(codec)
    override fun playback(codec: CodecConfig): AudioPlayback = AndroidPlayback(codec)
    override fun encoder(codec: CodecConfig): AudioEncoder = OpusEncoder(codec)
    override fun decoder(codec: CodecConfig): AudioDecoder = OpusDecoder(codec)
    override fun musicEncoder(codec: CodecConfig): AudioEncoder = OpusEncoder(codec, music = true)
    override fun musicPlayback(codec: CodecConfig): AudioPlayback = AndroidPlayback(codec, music = true)
    override fun onAudioThreadStart() = Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
}
