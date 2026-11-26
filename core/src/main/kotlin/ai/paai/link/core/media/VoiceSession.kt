package ai.paai.link.core.media

import ai.paai.link.core.crypto.CryptoException
import ai.paai.link.core.crypto.MediaCipher
import ai.paai.link.core.crypto.ReplayWindow
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.protocol.MediaKind
import ai.paai.link.core.protocol.MediaPacket
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** How a session addresses its audio. */
sealed class MediaTopology {
    /** One-to-one call: send to the peer. */
    data class Direct(val peer: DeviceId) : MediaTopology()
    /** Group member: send to the hub; the hub fans out to everyone else. */
    data class GroupMember(val hub: DeviceId) : MediaTopology()
    /** Group hub (host): send to all members; forward each member's audio to the others. */
    data class GroupHub(val members: () -> List<DeviceId>) : MediaTopology()
}

data class MediaSessionConfig(
    val sessionId: DeviceId,
    val key: ByteArray,
    val codec: CodecConfig,
    val localStreamId: Int,
    val topology: MediaTopology,
    /** Full-duplex calls transmit immediately; push-to-talk starts silent. */
    val transmitInitially: Boolean = true,
)

data class MediaStats(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val lossPercent: Double = 0.0,
    val latePackets: Long = 0,
    val jitterMs: Int = 0,
    val bufferMs: Int = 0,
    val bitrate: Int = 0,
    val authFailures: Long = 0,
    val sendFailures: Long = 0,
    val forwarded: Long = 0,
) {
    /** 0 = no audio, 1 = poor, 2 = fair, 3 = good. */
    val quality: Int get() = when {
        packetsReceived == 0L -> 0
        lossPercent > 10 || jitterMs > 80 -> 1
        lossPercent > 3 || jitterMs > 40 -> 2
        else -> 3
    }
}

/**
 * One live voice session (a call, a group call or a PTT channel).
 *
 * Threads: one capture thread (mic → Opus → encrypt → UDP) and one playout thread
 * (jitter buffers → Opus decode / concealment → mix → speaker). The UDP receive thread of the node
 * calls [onPacket]. Nothing here runs on the Android main thread. [close] stops both threads and
 * releases the microphone, the audio track and every codec instance.
 */
class VoiceSession(
    private val node: LinkNode,
    private val engine: AudioEngine,
    config: MediaSessionConfig,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val sessionId: DeviceId = config.sessionId
    private val codec = config.codec
    private val streamId = config.localStreamId
    @Volatile private var topology = config.topology
    @Volatile private var cipher = MediaCipher(config.key)
    @Volatile private var previousCipher: MediaCipher? = null
    @Volatile private var previousCipherUntil = 0L

    @Volatile private var running = true
    @Volatile var muted = false
    @Volatile var transmitting = config.transmitInitially
    private val mutedSources = ConcurrentHashMap.newKeySet<DeviceId>()
    /** Authorisation gate (PTT hub: only the floor holder). Rejected audio is neither played nor relayed. */
    @Volatile var acceptSource: ((DeviceId) -> Boolean)? = null

    private val sources = ConcurrentHashMap<StreamKey, Source>()
    private val windows = ConcurrentHashMap<StreamKey, ReplayWindow>()
    private val bitrate = BitrateController(initial = codec.bitrate)

    private val sent = AtomicLong()
    private val sendFailures = AtomicLong()
    private val authFailures = AtomicLong()
    private val forwarded = AtomicLong()
    private val lastReceiveAt = AtomicLong(0)
    private var seq = 0L
    private var mediaClock = 0L

    private val _stats = MutableStateFlow(MediaStats(bitrate = codec.bitrate))
    val stats: StateFlow<MediaStats> = _stats.asStateFlow()
    private val _levels = MutableStateFlow<Map<DeviceId, Float>>(emptyMap())
    /** Recent speaking level per remote device (active-speaker indicator). */
    val levels: StateFlow<Map<DeviceId, Float>> = _levels.asStateFlow()
    @Volatile var localLevel = 0f
        private set

    private data class StreamKey(val src: DeviceId, val stream: Int)
    private inner class Source(val id: DeviceId) {
        val jitter = JitterBuffer(frameMs = codec.frameMs)
        val decoder = engine.decoder(codec)
        @Volatile var lastPacketAt = 0L
        @Volatile var level = 0f
    }

    private val capture = engine.capture(codec)
    private val playback = engine.playback(codec)
    private val encoder = engine.encoder(codec)
    private val captureThread: Thread
    private val playoutThread: Thread
    private val reporter: Job

    /** Time of the last authenticated packet from any remote stream (0 = none yet). */
    val lastPacketAt: Long get() = lastReceiveAt.get()

    init {
        capture.start()
        playback.start()
        captureThread = thread(name = "voice-capture", isDaemon = true) { captureLoop() }
        playoutThread = thread(name = "voice-playout", isDaemon = true) { playoutLoop() }
        reporter = scope.launch {
            while (isActive && running) {
                delay(2_000)
                publishStats()
                sendReports()
            }
        }
    }

    // ----------------------------------------------------------------- transmit

    private fun captureLoop() {
        engine.onAudioThreadStart()
        val frame = ShortArray(codec.frameSamples)
        val out = ByteArray(ProtocolLimits.MAX_MEDIA_PAYLOAD)
        while (running) {
            val n = try { capture.read(frame) } catch (e: Exception) { -1 }
            if (!running) break
            if (n <= 0) { Thread.sleep(5); continue }
            localLevel = if (muted || !transmitting) 0f else Pcm.level(frame, n)
            mediaClock += codec.frameSamples
            if (muted || !transmitting) continue
            val len = encoder.encode(frame, out)
            if (len <= 0) continue
            val payload = out.copyOf(len)
            val s = seq++
            val header = MediaPacket(MediaKind.VOICE, ProtocolLimits.MAX_HOPS, node.selfId, DeviceId.ZERO, sessionId, streamId, s, mediaClock and 0xffffffffL, ByteArray(0))
            val ct = try { cipher.encrypt(streamId, s, header.aad(), payload) } catch (e: Exception) { continue }
            val pkt = MediaPacket(MediaKind.VOICE, ProtocolLimits.MAX_HOPS, node.selfId, DeviceId.ZERO, sessionId, streamId, s, header.timestamp, ct)
            for (dst in targets()) {
                if (node.sendMedia(pkt.readdressed(dst, ProtocolLimits.MAX_HOPS))) sent.incrementAndGet() else sendFailures.incrementAndGet()
            }
        }
    }

    private fun targets(): List<DeviceId> = when (val t = topology) {
        is MediaTopology.Direct -> listOf(t.peer)
        is MediaTopology.GroupMember -> listOf(t.hub)
        is MediaTopology.GroupHub -> t.members().filter { it != node.selfId }
    }

    // ----------------------------------------------------------------- receive

    /** Called on the UDP thread for packets addressed to this device and this session. */
    fun onPacket(pkt: MediaPacket) {
        if (!running || pkt.kind != MediaKind.VOICE || pkt.src == node.selfId) return
        if (acceptSource?.invoke(pkt.src) == false) return
        val key = StreamKey(pkt.src, pkt.streamId)
        val window = windows.getOrPut(key) { ReplayWindow() }
        if (!window.wouldAccept(pkt.seq)) return
        val payload = decrypt(pkt) ?: run { authFailures.incrementAndGet(); return }
        if (!window.accept(pkt.seq)) return
        val now = clock()
        lastReceiveAt.set(now)
        // Hub: relay the still-encrypted packet to every other member.
        (topology as? MediaTopology.GroupHub)?.let { hub ->
            for (m in hub.members()) {
                if (m == pkt.src || m == node.selfId) continue
                if (node.sendMedia(pkt.readdressed(m, ProtocolLimits.MAX_HOPS))) forwarded.incrementAndGet()
            }
        }
        if (pkt.src in mutedSources) return
        val src = sources.getOrPut(key) { Source(pkt.src) }
        src.lastPacketAt = now
        val mediaMs = pkt.timestamp * 1000 / codec.sampleRate
        src.jitter.put(pkt.seq, mediaMs, now, payload)
    }

    private fun decrypt(pkt: MediaPacket): ByteArray? {
        val aad = pkt.aad()
        return try {
            cipher.decrypt(pkt.streamId, pkt.seq, aad, pkt.ciphertext)
        } catch (e: CryptoException) {
            val prev = previousCipher
            if (prev != null && clock() < previousCipherUntil) {
                try { prev.decrypt(pkt.streamId, pkt.seq, aad, pkt.ciphertext) } catch (e2: CryptoException) { null }
            } else null
        }
    }

    // ----------------------------------------------------------------- playout

    private fun playoutLoop() {
        engine.onAudioThreadStart()
        val n = codec.frameSamples
        val acc = IntArray(n)
        val pcm = ShortArray(n)
        val mix = ShortArray(n)
        while (running) {
            java.util.Arrays.fill(acc, 0)
            val now = clock()
            for ((key, src) in sources) {
                if (now - src.lastPacketAt > SOURCE_IDLE_MS && !src.jitter.isPlaying) {
                    sources.remove(key)?.decoder?.close()
                    continue
                }
                val decoded = when (val f = src.jitter.poll()) {
                    is JitterBuffer.Frame.Packet -> src.decoder.decode(f.payload, pcm)
                    JitterBuffer.Frame.Missing -> src.decoder.decode(null, pcm)
                    JitterBuffer.Frame.Empty -> 0
                }
                if (decoded > 0) {
                    src.level = Pcm.level(pcm, decoded)
                    Pcm.accumulate(acc, pcm, decoded)
                } else {
                    src.level = 0f
                }
            }
            Pcm.clampInto(acc, mix)
            // Blocking write paces this loop at real time.
            val w = try { playback.write(mix, n) } catch (e: Exception) { -1 }
            if (w <= 0 && running) Thread.sleep(codec.frameMs.toLong())
        }
    }

    // ----------------------------------------------------------------- control

    fun setSourceMuted(id: DeviceId, mute: Boolean) {
        if (mute) mutedSources += id else mutedSources -= id
    }

    fun isSourceMuted(id: DeviceId) = id in mutedSources

    /** Group epoch change: switch to a new key, accepting the old one briefly for packets in flight. */
    fun rekey(newKey: ByteArray) {
        previousCipher = cipher
        previousCipherUntil = clock() + 3_000
        cipher = MediaCipher(newKey)
    }

    fun updateTopology(t: MediaTopology) {
        topology = t
    }

    /** Receiver report from [from] about our stream. */
    fun onReport(from: DeviceId, lossPermille: Int) {
        bitrate.onReport(from, lossPermille, clock())?.let {
            encoder.setBitrate(it)
            node.log.i("media", "bitrate -> ${it / 1000} kbps")
        }
    }

    private suspend fun sendReports() {
        val bySender = sources.values.groupBy { it.id }
        for ((sender, list) in bySender) {
            val worst = list.maxOf { it.jitter.lossPermille }
            val jitter = list.maxOf { it.jitter.jitterMs }
            val dst = when (val t = topology) {
                is MediaTopology.GroupMember -> t.hub // the hub forwards nothing upstream; report link quality to it
                else -> sender
            }
            node.send(dst, AppMessage.MediaReport(sessionId, worst, jitter))
        }
    }

    private fun publishStats() {
        val all = sources.values
        val received = all.sumOf { it.jitter.received }
        val lost = all.sumOf { it.jitter.lost }
        _stats.value = MediaStats(
            packetsSent = sent.get(),
            packetsReceived = received,
            lossPercent = if (received + lost == 0L) 0.0 else lost * 100.0 / (received + lost),
            latePackets = all.sumOf { it.jitter.late },
            jitterMs = all.maxOfOrNull { it.jitter.jitterMs } ?: 0,
            bufferMs = all.maxOfOrNull { it.jitter.bufferedMs } ?: 0,
            bitrate = bitrate.bitrate,
            authFailures = authFailures.get(),
            sendFailures = sendFailures.get(),
            forwarded = forwarded.get(),
        )
        val now = clock()
        _levels.value = all.filter { now - it.lastPacketAt < 600 }.groupBy { it.id }.mapValues { e -> e.value.maxOf { it.level } }
    }

    /** Refresh stats/levels now (UI polling at a faster rate than the report loop). */
    fun refresh() = publishStats()

    fun close() {
        if (!running) return
        running = false
        reporter.cancel()
        runCatching { capture.stop() }
        runCatching { playback.stop() }
        captureThread.join(1_000)
        playoutThread.join(1_000)
        runCatching { capture.close() }
        runCatching { playback.close() }
        runCatching { encoder.close() }
        for (s in sources.values) runCatching { s.decoder.close() }
        sources.clear()
        node.log.i("media", "session ${sessionId.short} closed, audio resources released")
    }

    val isClosed: Boolean get() = !running

    companion object {
        const val SOURCE_IDLE_MS = 3_000L
    }
}

/** Dispatches incoming media packets and receiver reports to the active sessions. */
class MediaRouter(private val node: LinkNode, scope: CoroutineScope) {
    private val sessions = ConcurrentHashMap<DeviceId, VoiceSession>()

    init {
        node.mediaHandler = { pkt -> sessions[pkt.sessionId]?.onPacket(pkt) }
        scope.launch {
            node.incoming.collect { inc ->
                val m = inc.message
                if (m is AppMessage.MediaReport) sessions[m.sessionId]?.onReport(inc.from, m.lossPermille)
            }
        }
    }

    fun register(session: VoiceSession) { sessions[session.sessionId] = session }
    fun unregister(session: VoiceSession) { sessions.remove(session.sessionId, session) }
}
