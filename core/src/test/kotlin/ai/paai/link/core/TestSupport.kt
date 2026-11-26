package ai.paai.link.core

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
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.fail

/**
 * Audio engine that "captures" a constant tone [value] and records what is played. The fake codec
 * carries the first sample value in two bytes, so the receiver can tell which device it hears.
 */
class FakeAudioEngine(private val value: Short) : AudioEngine {
    val capturesOpened = AtomicInteger(); val capturesClosed = AtomicInteger()
    val playbacksOpened = AtomicInteger(); val playbacksClosed = AtomicInteger()
    val encodersOpened = AtomicInteger(); val encodersClosed = AtomicInteger()
    val decodersOpened = AtomicInteger(); val decodersClosed = AtomicInteger()
    /** Distinct non-zero sample values played, with how many frames each. */
    val heard = ConcurrentHashMap<Int, AtomicLong>()
    val framesPlayed = AtomicLong()
    @Volatile var lastBitrate = 0

    fun heardCount(v: Int): Long = heard[v]?.get() ?: 0

    fun allReleased(): Boolean =
        capturesOpened.get() == capturesClosed.get() && playbacksOpened.get() == playbacksClosed.get() &&
            encodersOpened.get() == encodersClosed.get() && decodersOpened.get() == decodersClosed.get()

    override fun capture(codec: CodecConfig): AudioCapture {
        capturesOpened.incrementAndGet()
        return object : AudioCapture {
            @Volatile var on = false
            override fun start() { on = true }
            override fun read(frame: ShortArray): Int {
                if (!on) return -1
                Thread.sleep(codec.frameMs.toLong())
                frame.fill(value)
                return frame.size
            }
            override fun stop() { on = false }
            override fun close() { capturesClosed.incrementAndGet() }
        }
    }

    override fun playback(codec: CodecConfig): AudioPlayback {
        playbacksOpened.incrementAndGet()
        return object : AudioPlayback {
            @Volatile var on = false
            override fun start() { on = true }
            override fun write(frame: ShortArray, samples: Int): Int {
                if (!on) return -1
                Thread.sleep(codec.frameMs.toLong())
                framesPlayed.incrementAndGet()
                val v = frame[0].toInt()
                if (v != 0) heard.getOrPut(v) { AtomicLong() }.incrementAndGet()
                return samples
            }
            override fun stop() { on = false }
            override fun close() { playbacksClosed.incrementAndGet() }
        }
    }

    override fun encoder(codec: CodecConfig): AudioEncoder {
        encodersOpened.incrementAndGet()
        return object : AudioEncoder {
            override fun encode(pcm: ShortArray, out: ByteArray): Int {
                out[0] = (pcm[0].toInt() shr 8).toByte(); out[1] = pcm[0].toByte()
                return 2
            }
            override fun setBitrate(bitsPerSecond: Int) { lastBitrate = bitsPerSecond }
            override fun close() { encodersClosed.incrementAndGet() }
        }
    }

    override fun decoder(codec: CodecConfig): AudioDecoder {
        decodersOpened.incrementAndGet()
        return object : AudioDecoder {
            override fun decode(packet: ByteArray?, out: ShortArray, fec: Boolean): Int {
                val v = if (packet == null) 0 else ((packet[0].toInt() shl 8) or (packet[1].toInt() and 0xff)).toShort()
                out.fill(v)
                return out.size
            }
            override fun close() { decodersClosed.incrementAndGet() }
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
            tcpPort = tcpPort, udpPort = 0, bindAddress = InetAddress.getLoopbackAddress(),
            advertIntervalMs = 200, pingIntervalMs = 300, linkTimeoutMs = 3_000, routeTimeoutMs = 1_500,
            reconnectWindowMs = 15_000,
        ),
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
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (runCatching(check).getOrDefault(false)) return@runBlocking
        delay(50)
    }
    fail("timed out waiting for $what")
}

fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }
