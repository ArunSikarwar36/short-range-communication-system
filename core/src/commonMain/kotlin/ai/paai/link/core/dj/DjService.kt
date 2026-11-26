package ai.paai.link.core.dj

import ai.paai.link.core.crypto.MediaCipher
import ai.paai.link.core.crypto.ReplayWindow
import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.group.GroupRole
import ai.paai.link.core.group.GroupStatus
import ai.paai.link.core.media.AudioDecoder
import ai.paai.link.core.media.AudioEncoder
import ai.paai.link.core.media.AudioEngine
import ai.paai.link.core.media.AudioPlayback
import ai.paai.link.core.media.JitterBuffer
import ai.paai.link.core.media.MediaCoordinator
import ai.paai.link.core.node.Incoming
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.protocol.MediaKind
import ai.paai.link.core.protocol.MediaPacket
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.WorkerThread
import ai.paai.link.core.util.currentTimeMillis
import ai.paai.link.core.util.sleepMillis
import ai.paai.link.core.util.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

enum class DjRole { NONE, DJ, LISTENER }

data class DjTrackInfo(val trackId: DeviceId, val title: String, val artist: String, val durationMs: Long)

data class DjRequestInfo(val from: DeviceId, val name: String, val text: String, val at: Long)

data class DjState(
    val role: DjRole = DjRole.NONE,
    val sessionId: DeviceId? = null,
    val djName: String = "",
    val track: DjTrackInfo? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    /** DJ: members receiving the music. */
    val listeners: Int = 0,
    /** DJ: song requests from listeners, newest last. */
    val requests: List<DjRequestInfo> = emptyList(),
    /** DJ: the current track ended. */
    val ended: Boolean = false,
)

/** A song decoded by the platform: 48 kHz mono 16-bit PCM. */
interface MusicSource : AutoCloseable {
    /** Fills [pcm] and returns the number of samples; 0 at the end of the track. Blocks while decoding. */
    fun read(pcm: ShortArray): Int
}

/**
 * DJ Mode: the group host plays songs from its own phone and every group member hears them on
 * theirs. Music goes as encrypted media packets (Opus tuned for music) straight from the host to
 * each member, who plays it after a short buffer so that all phones play together closely.
 * Only music the user has on the phone is streamed; nothing is captured from other apps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DjService(
    private val node: LinkNode,
    private val groups: GroupManager,
    private val media: MediaCoordinator,
    private val engine: AudioEngine,
    private val scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
    private val bufferMs: Int = 300,
) {
    private val actor = Dispatchers.Default.limitedParallelism(1)
    private val _state = MutableStateFlow(DjState())
    val state: StateFlow<DjState> = _state.asStateFlow()

    private val lock = Lock()
    // DJ side
    private var cipher: MediaCipher? = null
    private var encoder: AudioEncoder? = null
    private var source: MusicSource? = null
    private var streamer: WorkerThread? = null
    @Volatile private var streaming = false
    @Volatile private var paused = false
    private var seq = 0L
    // listener side
    private var listen: Listener? = null

    init {
        scope.launch { node.incoming.collect { withContext(actor) { onIncoming(it) } } }
        scope.launch {
            groups.group.collect { g ->
                withContext(actor) {
                    val s = _state.value
                    if (s.role == DjRole.DJ && (g == null || g.role != GroupRole.HOST)) stopLocked(notify = g != null)
                    if (s.role == DjRole.LISTENER && g == null) listenerStop()
                    if (s.role == DjRole.DJ && g != null) _state.update { it.copy(listeners = g.members.size - 1) }
                }
            }
        }
    }

    // ================================================================== DJ (group host)

    /** Start DJ Mode for the current group; this phone must host it. */
    suspend fun start(): Result<Unit> = withContext(actor) {
        val g = groups.group.value ?: return@withContext Result.failure(IllegalStateException("Start or join a group first"))
        if (g.role != GroupRole.HOST || g.status != GroupStatus.ACTIVE) return@withContext Result.failure(IllegalStateException("Only the group host can be the DJ"))
        if (_state.value.role == DjRole.DJ) return@withContext Result.success(Unit)
        if (_state.value.role == DjRole.LISTENER) listenerStop()
        val key = MediaCipher.newKey()
        val sessionId = DeviceId.random()
        cipher = MediaCipher(key)
        encoder = engine.musicEncoder(CODEC)
        seq = 0
        _state.value = DjState(DjRole.DJ, sessionId, node.displayName, listeners = g.members.size - 1)
        for (m in members()) node.send(m, AppMessage.DjStart(sessionId, g.groupId, node.displayName, key, CODEC))
        node.log.i(TAG, "DJ Mode started for '${g.name}'")
        Result.success(Unit)
    }

    /** Play [track] from the start (replaces the current one). The service closes [music] when done. */
    suspend fun play(track: DjTrackInfo, music: MusicSource) = withContext(actor) {
        val s = _state.value
        if (s.role != DjRole.DJ) { music.close(); return@withContext }
        stopStreaming()
        source = music
        paused = false
        _state.value = s.copy(track = track, playing = true, positionMs = 0, ended = false)
        for (m in members()) {
            node.send(m, AppMessage.DjTrack(s.sessionId!!, track.trackId, track.title, track.artist, track.durationMs))
            node.send(m, AppMessage.DjPlayback(s.sessionId, true, 0))
        }
        streaming = true
        streamer = WorkerThread("dj-stream") { streamLoop(s.sessionId!!, music) }
    }

    suspend fun pause() = setPlaying(false)
    suspend fun resume() = setPlaying(true)

    private suspend fun setPlaying(playing: Boolean) = withContext(actor) {
        val s = _state.value
        if (s.role != DjRole.DJ || s.track == null || s.ended) return@withContext
        paused = !playing
        _state.value = s.copy(playing = playing)
        for (m in members()) node.send(m, AppMessage.DjPlayback(s.sessionId!!, playing, s.positionMs))
    }

    /** End DJ Mode (DJ) or stop listening (listener). */
    suspend fun stop() = withContext(actor) {
        when (_state.value.role) {
            DjRole.DJ -> stopLocked(notify = true)
            DjRole.LISTENER -> listenerStop()
            DjRole.NONE -> {}
        }
    }

    fun clearRequest(request: DjRequestInfo) = _state.update { it.copy(requests = it.requests - request) }

    private fun members(): List<DeviceId> {
        val g = groups.group.value ?: return emptyList()
        return g.members.map { it.id }.filter { it != node.selfId }
    }

    private suspend fun stopLocked(notify: Boolean) {
        val s = _state.value
        stopStreaming()
        lock.withLock { encoder?.close(); encoder = null }
        if (notify && s.sessionId != null) for (m in members()) node.send(m, AppMessage.DjStop(s.sessionId))
        cipher = null
        _state.value = DjState()
        node.log.i(TAG, "DJ Mode stopped")
    }

    private fun stopStreaming() {
        streaming = false
        streamer?.join(1_000)
        streamer = null
        source?.let { runCatching { it.close() } }
        source = null
    }

    /** Streams one track in real time; a short burst first fills the listeners' buffers. */
    private fun streamLoop(sessionId: DeviceId, music: MusicSource) {
        engine.onAudioThreadStart()
        val frame = ShortArray(CODEC.frameSamples)
        val out = ByteArray(ProtocolLimits.MAX_MEDIA_PAYLOAD)
        var frames = 0L
        var startAt = clock()
        var pausedAt = 0L
        while (streaming) {
            if (paused) {
                if (pausedAt == 0L) pausedAt = clock()
                sleepMillis(20)
                continue
            }
            if (pausedAt != 0L) { startAt += clock() - pausedAt; pausedAt = 0L } // keep real-time pacing after a pause
            val n = try { music.read(frame) } catch (e: Exception) { 0 }
            if (n <= 0) {
                scope.launch(actor) { trackEnded(sessionId) }
                break
            }
            if (n < frame.size) frame.fill(0, n, frame.size)
            val len = lock.withLock { encoder?.encode(frame, out) ?: -1 }
            if (len <= 0) continue
            val c = cipher ?: break
            val s = seq++
            val ts = (frames * CODEC.frameSamples) and 0xffffffffL
            val header = MediaPacket(MediaKind.MUSIC, ProtocolLimits.MAX_HOPS, node.selfId, DeviceId.ZERO, sessionId, STREAM, s, ts, ByteArray(0))
            val ct = try { c.encrypt(STREAM, s, header.aad(), out.copyOf(len)) } catch (e: Exception) { continue }
            val pkt = MediaPacket(MediaKind.MUSIC, ProtocolLimits.MAX_HOPS, node.selfId, DeviceId.ZERO, sessionId, STREAM, s, ts, ct)
            for (m in members()) node.sendMedia(pkt.readdressed(m, ProtocolLimits.MAX_HOPS))
            frames++
            val positionMs = frames * CODEC.frameMs
            if (frames % 25 == 0L) _state.update { if (it.sessionId == sessionId) it.copy(positionMs = positionMs) else it }
            // Real-time pacing, PREROLL frames ahead of the clock.
            val due = startAt + (frames - PREROLL) * CODEC.frameMs
            val wait = due - clock()
            if (wait > 0) sleepMillis(wait)
        }
    }

    private suspend fun trackEnded(sessionId: DeviceId) {
        val s = _state.value
        if (s.sessionId != sessionId) return
        _state.value = s.copy(playing = false, ended = true)
        for (m in members()) node.send(m, AppMessage.DjPlayback(sessionId, false, s.positionMs))
    }

    // ================================================================== listener

    private inner class Listener(val sessionId: DeviceId, val dj: DeviceId, key: ByteArray, val codec: CodecConfig) {
        val cipher = MediaCipher(key)
        val window = ReplayWindow()
        val jitter = JitterBuffer(codec.frameMs, minDelayMs = bufferMs, maxDelayMs = bufferMs * 3, maxExtraMs = 400, capacity = 100)
        val decoder: AudioDecoder = engine.decoder(codec)
        val playback: AudioPlayback = engine.musicPlayback(codec)
        @Volatile var running = true
        val thread: WorkerThread

        init {
            playback.start()
            thread = WorkerThread("dj-playout") { playout() }
        }

        fun onPacket(pkt: MediaPacket) {
            if (!running || pkt.kind != MediaKind.MUSIC || pkt.src != dj) return
            if (!window.wouldAccept(pkt.seq)) return
            val payload = try { cipher.decrypt(pkt.streamId, pkt.seq, pkt.aad(), pkt.ciphertext) } catch (e: Exception) { return }
            if (!window.accept(pkt.seq)) return
            jitter.put(pkt.seq, pkt.timestamp * 1000 / codec.sampleRate, clock(), payload)
        }

        private fun playout() {
            engine.onAudioThreadStart()
            val pcm = ShortArray(codec.frameSamples)
            val silence = ShortArray(codec.frameSamples)
            while (running) {
                val n = when (val f = jitter.poll()) {
                    is JitterBuffer.Frame.Packet -> decoder.decode(f.payload, pcm)
                    JitterBuffer.Frame.Missing -> decoder.decode(null, pcm)
                    JitterBuffer.Frame.Empty -> 0
                }
                val w = if (n > 0) playback.write(pcm, n) else playback.write(silence, silence.size)
                if (w <= 0 && running) sleepMillis(codec.frameMs.toLong())
            }
        }

        fun close() {
            running = false
            thread.join(1_000)
            runCatching { playback.stop() }
            runCatching { playback.close() }
            runCatching { decoder.close() }
        }
    }

    /** Listener: ask the DJ for a song. */
    suspend fun request(text: String) = withContext(actor) {
        val s = _state.value
        val g = groups.group.value ?: return@withContext
        if (s.role != DjRole.LISTENER || s.sessionId == null || text.isBlank()) return@withContext
        g.host?.let { node.send(it, AppMessage.DjRequest(s.sessionId, text.trim().take(120))) }
    }

    private fun listenerStop() {
        val l = listen ?: run { if (_state.value.role == DjRole.LISTENER) _state.value = DjState(); return }
        listen = null
        media.router.unregisterHandler(l.sessionId)
        l.close()
        _state.value = DjState()
    }

    private suspend fun onIncoming(inc: Incoming) {
        val g = groups.group.value
        when (val m = inc.message) {
            is AppMessage.DjStart -> {
                if (g == null || g.groupId != m.groupId || g.host != inc.from) return
                if (_state.value.role == DjRole.DJ) stopLocked(notify = false)
                listenerStop()
                val l = Listener(m.sessionId, inc.from, m.mediaKey, m.codec.validate())
                listen = l
                media.router.registerHandler(m.sessionId) { l.onPacket(it) }
                _state.value = DjState(DjRole.LISTENER, m.sessionId, m.djName)
                node.log.i(TAG, "listening to ${m.djName}'s DJ Mode")
            }
            is AppMessage.DjTrack -> if (fromDj(inc, m.sessionId)) {
                listen?.jitter?.clear() // switch songs immediately
                _state.update { it.copy(track = DjTrackInfo(m.trackId, m.title, m.artist, m.durationMs), positionMs = 0) }
            }
            is AppMessage.DjPlayback -> if (fromDj(inc, m.sessionId)) {
                if (!m.playing) listen?.jitter?.clear() // pause on every phone at once
                _state.update { it.copy(playing = m.playing, positionMs = m.positionMs) }
            }
            is AppMessage.DjStop -> if (fromDj(inc, m.sessionId)) listenerStop()
            is AppMessage.DjRequest -> {
                val s = _state.value
                if (s.role != DjRole.DJ || s.sessionId != m.sessionId || g == null || g.members.none { it.id == inc.from }) return
                val r = DjRequestInfo(inc.from, node.peerName(inc.from), m.text, clock())
                _state.value = s.copy(requests = (s.requests + r).takeLast(20))
            }
            else -> {}
        }
    }

    private fun fromDj(inc: Incoming, sessionId: DeviceId): Boolean {
        val s = _state.value
        return s.role == DjRole.LISTENER && s.sessionId == sessionId && listen?.dj == inc.from
    }

    companion object {
        private const val TAG = "dj"
        private const val STREAM = 1
        private const val PREROLL = 15L
        /** Music: 48 kHz mono, 20 ms frames, 64 kbit/s Opus. */
        val CODEC = CodecConfig(48_000, 20, 64_000)
    }
}
