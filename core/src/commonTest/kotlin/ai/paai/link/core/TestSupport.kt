package ai.paai.link.core

import ai.paai.link.core.node.LogBuffer
import ai.paai.link.core.util.sleepMillis
import ai.paai.link.core.util.currentTimeMillis
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.incrementAndFetch
import ai.paai.link.core.util.ConcurrentMap
import kotlin.concurrent.Volatile
import ai.paai.link.core.transport.Net
import ai.paai.link.core.call.CallManager
import ai.paai.link.core.call.CallTimer
import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.crypto.Primitives
import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.media.AudioCapture
import ai.paai.link.core.media.AudioDecoder
import ai.paai.link.core.media.AudioEncoder
import ai.paai.link.core.media.AudioEngine
import ai.paai.link.core.media.AudioPlayback
import ai.paai.link.core.media.MediaCoordinator
import ai.paai.link.core.messaging.InMemoryMessageStore
import ai.paai.link.core.messaging.MessagingConfig
import ai.paai.link.core.messaging.MessagingService
import ai.paai.link.core.node.InMemoryTrustStore
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.node.NodeConfig
import ai.paai.link.core.node.TrustedPeer
import ai.paai.link.core.protocol.CodecConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.fail

/**
 * Audio engine that "captures" a constant tone [value] and records what is played. The fake codec
 * carries the first sample value in two bytes, so the receiver can tell which device it hears.
 */
class FakeAudioEngine(private val value: Short) : AudioEngine {
    val capturesOpened = AtomicInt(0); val capturesClosed = AtomicInt(0)
    val playbacksOpened = AtomicInt(0); val playbacksClosed = AtomicInt(0)
    val encodersOpened = AtomicInt(0); val encodersClosed = AtomicInt(0)
    val decodersOpened = AtomicInt(0); val decodersClosed = AtomicInt(0)
    /** Distinct non-zero sample values played, with how many frames each. */
    val heard = ConcurrentMap<Int, AtomicLong>()
    val framesPlayed = AtomicLong(0L)
    @Volatile var lastBitrate = 0

    fun heardCount(v: Int): Long = heard[v]?.load() ?: 0

    fun allReleased(): Boolean =
        capturesOpened.load() == capturesClosed.load() && playbacksOpened.load() == playbacksClosed.load() &&
            encodersOpened.load() == encodersClosed.load() && decodersOpened.load() == decodersClosed.load()

    override fun capture(codec: CodecConfig): AudioCapture {
        capturesOpened.incrementAndFetch()
        return object : AudioCapture {
            @Volatile var on = false
            override fun start() { on = true }
            override fun read(frame: ShortArray): Int {
                if (!on) return -1
                sleepMillis(codec.frameMs.toLong())
                frame.fill(value)
                return frame.size
            }
            override fun stop() { on = false }
            override fun close() { capturesClosed.incrementAndFetch() }
        }
    }

    override fun playback(codec: CodecConfig): AudioPlayback {
        playbacksOpened.incrementAndFetch()
        return object : AudioPlayback {
            @Volatile var on = false
            override fun start() { on = true }
            override fun write(frame: ShortArray, samples: Int): Int {
                if (!on) return -1
                sleepMillis(codec.frameMs.toLong())
                framesPlayed.incrementAndFetch()
                val v = frame[0].toInt()
                if (v != 0) heard.getOrPut(v) { AtomicLong(0L) }.incrementAndFetch()
                return samples
            }
            override fun stop() { on = false }
            override fun close() { playbacksClosed.incrementAndFetch() }
        }
    }

    override fun encoder(codec: CodecConfig): AudioEncoder {
        encodersOpened.incrementAndFetch()
        return object : AudioEncoder {
            override fun encode(pcm: ShortArray, out: ByteArray): Int {
                out[0] = (pcm[0].toInt() shr 8).toByte(); out[1] = pcm[0].toByte()
                return 2
            }
            override fun setBitrate(bitsPerSecond: Int) { lastBitrate = bitsPerSecond }
            override fun close() { encodersClosed.incrementAndFetch() }
        }
    }

    override fun decoder(codec: CodecConfig): AudioDecoder {
        decodersOpened.incrementAndFetch()
        return object : AudioDecoder {
            override fun decode(packet: ByteArray?, out: ShortArray, fec: Boolean): Int {
                val v = if (packet == null) 0 else ((packet[0].toInt() shl 8) or (packet[1].toInt() and 0xff)).toShort()
                out.fill(v)
                return out.size
            }
            override fun close() { decodersClosed.incrementAndFetch() }
        }
    }
}

val FAST_TIMERS: (CallTimer) -> Long = {
    when (it) {
        CallTimer.INVITE -> 3_000
        CallTimer.RING -> 6_000
        CallTimer.CONNECT -> 4_000
        CallTimer.RECONNECT -> 2_000
        CallTimer.LINGER -> 300
    }
}

class TestDevice(
    val name: String,
    tone: Short,
    val keyPair: KeyPair = Primitives.generateKeyPair(),
    val trust: InMemoryTrustStore = InMemoryTrustStore(),
    tcpPort: Int = 0,
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val identity = LocalIdentity(keyPair, name)
    val id = identity.deviceId
    val engine = FakeAudioEngine(tone)
    val node = LinkNode(
        identity, trust,
        NodeConfig(
            tcpPort = tcpPort, udpPort = 0, bindHost = "127.0.0.1",
            advertIntervalMs = 200, pingIntervalMs = 300, linkTimeoutMs = 3_000, routeTimeoutMs = 1_500,
            reconnectWindowMs = 15_000,
        ),
        log = LogBuffer(sink = { println("[$name ${it.level} ${it.tag}] ${it.message}") }),
    )
    val media = MediaCoordinator(node, engine, scope)
    val calls = CallManager(node, media, scope, timerMs = FAST_TIMERS, stallMs = 1_200)
    val groups = GroupManager(node, media, scope, isCallActive = { calls.state.value.phase.isLive })
    val store = InMemoryMessageStore()
    val messages = MessagingService(node, store, groups, scope, MessagingConfig(ackTimeoutMs = 1_000))

    fun start() = runBlocking { node.start() }

    fun stop() = runBlocking {
        node.stop()
        scope.cancel()
    }

    fun connectTo(other: TestDevice) = runBlocking {
        node.connect("127.0.0.1", other.node.tcpPort).getOrThrow()
    }
}

/** Mark devices as previously paired (as if they had verified each other's codes earlier). */
fun trustEachOther(vararg devices: TestDevice) = runBlocking {
    for (a in devices) for (b in devices) if (a !== b) a.trust.save(TrustedPeer(b.id, b.keyPair.publicKey, b.name, 0))
}

fun eventually(timeoutMs: Long = 8_000, what: String = "condition", check: () -> Boolean) = runBlocking {
    val deadline = currentTimeMillis() + timeoutMs
    while (currentTimeMillis() < deadline) {
        if (runCatching(check).getOrDefault(false)) return@runBlocking
        delay(50)
    }
    fail("timed out waiting for $what")
}

fun freePort(): Int = Net.listen(0, "127.0.0.1").use { it.localPort }

/** Thread-safe list for collecting events from coroutines in tests. */
class SyncList<T> : AbstractList<T>() {
    private val lock = ai.paai.link.core.util.Lock()
    private val items = ArrayList<T>()
    fun add(item: T) { lock.lock(); try { items.add(item) } finally { lock.unlock() } }
    operator fun plusAssign(item: T) = add(item)
    override val size: Int get() { lock.lock(); try { return items.size } finally { lock.unlock() } }
    override fun get(index: Int): T { lock.lock(); try { return items[index] } finally { lock.unlock() } }
    fun snapshot(): List<T> { lock.lock(); try { return items.toList() } finally { lock.unlock() } }
}
