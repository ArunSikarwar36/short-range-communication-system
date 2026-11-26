package ai.paai.link.audio

import ai.paai.link.core.dj.MusicSource
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.nio.ByteOrder

/** Title, artist and length of a song file. */
data class SongInfo(val uri: Uri, val title: String, val artist: String, val durationMs: Long)

object SongInfoReader {
    fun read(context: Context, uri: Uri): SongInfo {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Song"
            val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            SongInfo(uri, title.take(80), artist.take(80), duration)
        } catch (e: Exception) {
            SongInfo(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "Song", "", 0)
        } finally {
            runCatching { r.release() }
        }
    }
}

/**
 * Decodes a song file on this phone (MP3, AAC/M4A, FLAC, OGG, WAV… whatever Android decodes) with
 * MediaCodec and delivers 48 kHz mono 16-bit PCM for DJ Mode. Stereo is mixed down; other sample
 * rates are resampled (linear interpolation).
 */
class AndroidMusicSource(context: Context, uri: Uri) : MusicSource {
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private var srcRate = 44_100
    private var channels = 2
    private var inputDone = false
    private var outputDone = false
    private val info = MediaCodec.BufferInfo()
    // Decoded mono samples at the source rate, waiting to be resampled.
    private var pending = ShortArray(0)
    private var pendingLen = 0
    private var pos = 0.0 // read position in [pending] (fractional, for resampling)

    init {
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: throw IllegalArgumentException("no audio in this file")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        srcRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
    }

    private fun feed() {
        if (inputDone) return
        val i = codec.dequeueInputBuffer(5_000)
        if (i < 0) return
        val buf = codec.getInputBuffer(i)!!
        val n = extractor.readSampleData(buf, 0)
        if (n < 0) {
            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    /** Appends decoded, mono-mixed samples to [pending]; false at the end of the song. */
    private fun drain(): Boolean {
        while (!outputDone) {
            feed()
            val o = codec.dequeueOutputBuffer(info, 5_000)
            when {
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                o >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val out = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    out.position(info.offset / 2)
                    val total = info.size / 2
                    val frames = total / channels
                    if (pendingLen + frames > pending.size) {
                        val keep = pending.copyOfRange(pos.toInt().coerceAtMost(pendingLen), pendingLen)
                        pos -= pos.toInt().coerceAtMost(pendingLen)
                        pending = keep.copyOf(maxOf(keep.size + frames, 8192))
                        pendingLen = keep.size
                    }
                    val tmp = ShortArray(total)
                    out.get(tmp, 0, total)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (c in 0 until channels) sum += tmp[f * channels + c]
                        pending[pendingLen++] = (sum / channels).toShort()
                    }
                    codec.releaseOutputBuffer(o, false)
                    if (frames > 0) return true
                }
            }
        }
        return false
    }

    override fun read(pcm: ShortArray): Int {
        val step = srcRate / 48_000.0
        var n = 0
        while (n < pcm.size) {
            val i = pos.toInt()
            if (i + 1 >= pendingLen) {
                if (!drain()) break
                continue
            }
            val frac = pos - i
            pcm[n++] = (pending[i] * (1 - frac) + pending[i + 1] * frac).toInt().toShort()
            pos += step
        }
        return n
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}
