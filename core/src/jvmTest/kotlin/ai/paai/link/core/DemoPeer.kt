package ai.paai.link.core

import ai.paai.link.core.call.CallManager
import ai.paai.link.core.call.CallPhase
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
import ai.paai.link.core.messaging.MessagingService
import ai.paai.link.core.node.InMemoryTrustStore
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.node.LogBuffer
import ai.paai.link.core.node.NodeConfig
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.util.hexToBytes
import ai.paai.link.core.util.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * "Desktop test phone": runs the real core stack on the PC so an emulator (which has no
 * Wi-Fi Direct radio) has someone to pair, message and call with.
 *
 *  - pairing requests are confirmed automatically (the code is printed);
 *  - text messages are answered with "Echo: …";
 *  - incoming calls are answered after 2 s and the caller's Opus audio is sent straight back
 *    (an echo test of the whole encrypted media path), since the PC side has no Opus codec.
 *
 * Run: gradlew :core:demoPeer   (listens on TCP 47710 / UDP 47711)
 * From the emulator: Diagnostics → Connect by address → 10.0.2.2:47710
 * Commands: write "call" / "hangup" / "msg <text>" / "loc <lat> <lon>" (share that position) /
 * "sos <note>" into D:\android-build\demo-peer.cmd
 */
/** Key and command files: D:/android-build on the development PC, the temp folder elsewhere (CI on macOS). */
private val demoDir: String = System.getenv("SRCS_DEMO_DIR")
    ?: if (File("D:/android-build").isDirectory) "D:/android-build" else System.getProperty("java.io.tmpdir")

fun main() = runBlocking {
    val keyFile = File(System.getProperty("demo.key", "$demoDir/demo-peer.key"))
    val kp = if (keyFile.exists()) KeyPair.fromPrivate(keyFile.readText().trim().hexToBytes())
    else Primitives.generateKeyPair().also { keyFile.writeText(it.privateKeyCopy().toHex()) }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = LogBuffer { println("[${it.level}] ${it.tag}: ${it.message}") }
    val node = LinkNode(LocalIdentity(kp, "Desktop test phone"), InMemoryTrustStore(), NodeConfig(tcpPort = 47710, udpPort = 47711), log)
    val media = MediaCoordinator(node, EchoEngine(), scope)
    val calls = CallManager(node, media, scope)
    val groups = GroupManager(node, media, scope, isCallActive = { calls.state.value.phase.isLive })
    val messages = MessagingService(node, InMemoryMessageStore(), groups, scope)
    val location = ai.paai.link.core.location.LocationService(node, groups, scope)
    node.start()
    println("Desktop test phone ${node.selfId.short} listening on tcp ${node.tcpPort} / udp ${node.udpPort}")

    scope.launch {
        node.events.collect { e ->
            if (e is NodeEvent.PairingRequested) {
                println(">>> Pairing request from ${e.link.remoteName}, code ${e.link.sas} — confirming")
                node.confirmPairing(e.link.linkId, true)
            }
        }
    }
    scope.launch {
        messages.received.collect { m ->
            println(">>> Message from ${m.authorName}: ${m.body}")
            if (!m.isGroup) messages.send(m.author, "Echo: ${m.body}")
        }
    }
    scope.launch {
        calls.state.collect { s ->
            println(">>> Call: ${s.phase}${s.endReason?.let { " ($it)" } ?: ""}")
            if (s.phase == CallPhase.INCOMING_RINGING) {
                delay(2_000)
                calls.accept()
            }
        }
    }
    scope.launch {
        location.friends.collect { list -> list.forEach { println(">>> ${it.name} shares location %.5f, %.5f".format(it.lat, it.lon)) } }
    }
    scope.launch {
        location.sosReceived.collect { println(">>> SOS from ${it.name}: ${it.note} ${it.fix?.let { f -> "at %.5f, %.5f".format(f.lat, f.lon) } ?: "(no position)"}") }
    }
    scope.launch {
        groups.invites.collect { list -> list.forEach { println(">>> Joining group ${it.name}"); groups.acceptInvite(it.groupId) } }
    }
    scope.launch {
        while (true) {
            delay(5_000)
            calls.session.value?.let { s ->
                s.refresh()
                val st = s.stats.value
                println("    audio: sent ${st.packetsSent}, received ${st.packetsReceived}, loss ${"%.1f".format(st.lossPercent)}%, jitter ${st.jitterMs} ms, auth failures ${st.authFailures}")
            }
        }
    }
    val cmdFile = File(System.getProperty("demo.cmd", "$demoDir/demo-peer.cmd"))
    while (true) {
        delay(500)
        if (!cmdFile.exists()) continue
        val cmd = cmdFile.readText().trim()
        cmdFile.delete()
        val peer = node.peers.value.firstOrNull { it.trusted && it.reachable }?.id
        when {
            peer == null -> println("!!! no paired device connected")
            cmd == "call" -> println("calling: ${calls.call(peer)}")
            cmd == "hangup" -> calls.hangup()
            cmd.startsWith("msg ") -> messages.send(peer, cmd.removePrefix("msg "))
            cmd.startsWith("loc ") -> {
                val (lat, lon) = cmd.removePrefix("loc ").trim().split(Regex("[ ,]+")).map { it.toDouble() }
                location.onFix(ai.paai.link.core.location.GeoFix(lat, lon, 5f, System.currentTimeMillis()))
                if (!location.sharing.value.active) location.startSharing(toPaired = true, toGroup = false, durationMs = 3_600_000)
                println("sharing location %.5f, %.5f".format(lat, lon))
            }
            cmd.startsWith("sos") -> println("SOS sent to ${location.sendSos(cmd.removePrefix("sos").trim())}")
            else -> println("!!! unknown command $cmd")
        }
    }
}

/** Sends every received Opus packet back to its sender; silence (1-byte Opus DTX frame) otherwise. */
private class EchoEngine : AudioEngine {
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    override fun capture(codec: CodecConfig) = object : AudioCapture {
        @Volatile var on = false
        override fun start() { on = true }
        override fun read(frame: ShortArray): Int {
            if (!on) return -1
            Thread.sleep(codec.frameMs.toLong())
            return frame.size
        }
        override fun stop() { on = false }
        override fun close() {}
    }

    override fun playback(codec: CodecConfig) = object : AudioPlayback {
        @Volatile var on = false
        override fun start() { on = true }
        override fun write(frame: ShortArray, samples: Int): Int {
            if (!on) return -1
            Thread.sleep(codec.frameMs.toLong())
            return samples
        }
        override fun stop() { on = false }
        override fun close() {}
    }

    override fun encoder(codec: CodecConfig) = object : AudioEncoder {
        override fun encode(pcm: ShortArray, out: ByteArray): Int {
            val p = queue.poll() ?: byteArrayOf(0xF8.toByte()) // CELT FB 20 ms, empty frame
            p.copyInto(out)
            return p.size
        }
        override fun setBitrate(bitsPerSecond: Int) {}
        override fun close() { queue.clear() }
    }

    override fun decoder(codec: CodecConfig) = object : AudioDecoder {
        override fun decode(packet: ByteArray?, out: ShortArray, fec: Boolean): Int {
            if (packet != null && queue.size < 50) queue.add(packet.copyOf())
            out.fill(0)
            return out.size
        }
        override fun close() {}
    }
}
