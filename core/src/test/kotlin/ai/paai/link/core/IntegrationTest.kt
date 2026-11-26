package ai.paai.link.core

import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.call.EndReason
import ai.paai.link.core.group.GroupEvent
import ai.paai.link.core.group.GroupRole
import ai.paai.link.core.group.GroupStatus
import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.node.Incoming
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.util.Collections
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Multi-device tests over real loopback TCP and UDP sockets. They exercise the complete stack —
 * Noise handshakes, pairing, routing, sealing, media encryption, jitter buffers — with a fake
 * audio engine in place of the microphone and speaker.
 */
class IntegrationTest {
    private val devices = ArrayList<TestDevice>()

    private fun device(name: String, tone: Int, tcpPort: Int = 0, reuse: TestDevice? = null): TestDevice {
        val d = if (reuse != null) TestDevice(name, tone.toShort(), reuse.keyPair, reuse.trust, tcpPort) else TestDevice(name, tone.toShort(), tcpPort = tcpPort)
        devices += d
        return d
    }

    @After
    fun tearDown() {
        devices.forEach { runCatching { it.stop() } }
    }

    private fun started(vararg ds: TestDevice) = ds.forEach { it.start() }

    // ------------------------------------------------------------------ pairing

    @Test
    fun `pairing requires both users to confirm the same code`() {
        val a = device("A", 1000); val b = device("B", 2000)
        started(a, b)
        val paired = Collections.synchronizedList(ArrayList<NodeEvent>())
        a.scope.launch { a.node.events.collect { if (it is NodeEvent.Paired) paired += it } }
        a.connectTo(b)
        eventually(what = "pending link on both") { a.node.linkInfos.value.size == 1 && b.node.linkInfos.value.size == 1 }
        val la = a.node.linkInfos.value.single(); val lb = b.node.linkInfos.value.single()
        assertFalse(la.trusted); assertFalse(lb.trusted)
        assertEquals(la.sas, lb.sas)
        assertEquals(b.id, la.remoteId)
        // Unpaired devices cannot exchange application messages.
        assertFalse(runBlocking { a.node.send(b.id, AppMessage.CallRinging(DeviceId.random())) })

        runBlocking { a.node.confirmPairing(la.linkId, true) }
        Thread.sleep(300)
        assertFalse(a.node.linkInfos.value.single().trusted, "one confirmation is not enough")
        runBlocking { b.node.confirmPairing(lb.linkId, true) }
        eventually(what = "trusted") { a.node.linkInfos.value.single().trusted && b.node.linkInfos.value.single().trusted }
        assertTrue(a.node.isTrusted(b.id) && b.node.isTrusted(a.id))
        assertEquals(1, runBlocking { a.trust.loadAll().size })
        eventually(what = "paired event") { paired.isNotEmpty() }
        eventually(what = "reachable") { a.node.peers.value.any { it.id == b.id && it.reachable && it.trusted } }
    }

    @Test
    fun `declining pairing closes the link`() {
        val a = device("A", 1000); val b = device("B", 2000)
        started(a, b)
        val failures = Collections.synchronizedList(ArrayList<NodeEvent>())
        a.scope.launch { a.node.events.collect { if (it is NodeEvent.PairingFailed) failures += it } }
        a.connectTo(b)
        eventually { b.node.linkInfos.value.size == 1 }
        runBlocking { b.node.confirmPairing(b.node.linkInfos.value.single().linkId, false) }
        eventually(what = "links closed") { a.node.linkInfos.value.isEmpty() && b.node.linkInfos.value.isEmpty() }
        eventually(what = "failure event") { failures.isNotEmpty() }
        assertFalse(a.node.isTrusted(b.id))
    }

    // ------------------------------------------------------------------ relaying and messaging

    @Test
    fun `messages are relayed end to end and the relay cannot read them`() {
        val a = device("A", 1000); val b = device("B", 2000); val c = device("C", 3000)
        trustEachOther(a, b, c)
        started(a, b, c)
        val seenByRelay = Collections.synchronizedList(ArrayList<Incoming>())
        b.scope.launch { b.node.incoming.collect { seenByRelay += it } }
        a.connectTo(b); c.connectTo(b)
        eventually(what = "route A->C via B") { a.node.routes.value.any { it.dest == c.id && it.nextHop == b.id && it.hops == 2 } }
        val sent = runBlocking { a.messages.send(c.id, "hello over the relay") }
        eventually(what = "delivered at C") { c.store.all.any { it.body == "hello over the relay" && it.author == a.id } }
        eventually(what = "ack at A") { runBlocking { a.store.get(sent.messageId)!!.status } == MessageStatus.DELIVERED }
        assertTrue(seenByRelay.none { it.message is AppMessage.Text }, "relay must not decrypt")
        assertTrue(b.store.all.isEmpty())
        runBlocking { c.messages.markRead(a.id) }
        eventually(what = "read receipt") { runBlocking { a.store.get(sent.messageId)!!.status } == MessageStatus.READ }
    }

    @Test
    fun `queued message is delivered once when the peer appears`() {
        val a = device("A", 1000); val b = device("B", 2000)
        trustEachOther(a, b)
        started(a, b)
        val m = runBlocking { a.messages.send(b.id, "later") }
        assertEquals(MessageStatus.QUEUED, m.status)
        a.connectTo(b)
        eventually(what = "delivered") { runBlocking { a.store.get(m.messageId)!!.status } == MessageStatus.DELIVERED }
        Thread.sleep(1_500)
        assertEquals(1, b.store.all.count { it.body == "later" })
    }

    @Test
    fun `routes are withdrawn when the relay disappears`() {
        val a = device("A", 1000); val b = device("B", 2000); val c = device("C", 3000)
        trustEachOther(a, b, c)
        started(a, b, c)
        a.connectTo(b); c.connectTo(b)
        eventually { a.node.isReachable(c.id) }
        b.stop()
        eventually(what = "route to C removed") { !a.node.isReachable(c.id) && !a.node.isReachable(b.id) }
        assertTrue(a.node.peers.value.none { it.reachable })
    }

    @Test
    fun `dropped link is re-established automatically`() {
        val port = freePort()
        val a = device("A", 1000); val b1 = device("B", 2000, tcpPort = port)
        trustEachOther(a, b1)
        started(a, b1)
        a.connectTo(b1)
        eventually { a.node.isReachable(b1.id) }
        b1.stop()
        eventually { !a.node.isReachable(b1.id) }
        val b2 = device("B", 2000, tcpPort = port, reuse = b1)
        b2.start()
        eventually(timeoutMs = 10_000, what = "reconnected") { a.node.isReachable(b2.id) && a.node.linkInfos.value.singleOrNull()?.trusted == true }
    }

    // ------------------------------------------------------------------ calls

    @Test
    fun `one-to-one call with two-way audio, mute and clean teardown`() {
        val a = device("A", 1000); val b = device("B", 2000)
        trustEachOther(a, b)
        started(a, b)
        a.connectTo(b)
        eventually { a.node.isReachable(b.id) && b.node.isReachable(a.id) }

        assertTrue(a.calls.call(b.id))
        eventually(what = "ringing") { b.calls.state.value.phase == CallPhase.INCOMING_RINGING && a.calls.state.value.phase == CallPhase.REMOTE_RINGING }
        b.calls.accept()
        eventually(what = "active") { a.calls.state.value.phase == CallPhase.ACTIVE && b.calls.state.value.phase == CallPhase.ACTIVE }
        eventually(what = "B hears A and A hears B") { b.engine.heardCount(1000) > 10 && a.engine.heardCount(2000) > 10 }

        a.calls.setMuted(true)
        Thread.sleep(600)
        val before = b.engine.heardCount(1000)
        Thread.sleep(800)
        assertEquals(before, b.engine.heardCount(1000), "muted microphone must not be heard")
        assertEquals(CallPhase.ACTIVE, b.calls.state.value.phase, "keepalives keep a muted call alive")

        val finished = Collections.synchronizedList(ArrayList<ai.paai.link.core.call.CallState>())
        a.scope.launch { a.calls.finished.collect { finished += it } }
        Thread.sleep(100)
        b.calls.hangup()
        eventually(what = "ended") { a.calls.state.value.endReason == EndReason.HANGUP_REMOTE || a.calls.state.value.phase == CallPhase.IDLE }
        eventually(what = "idle") { a.calls.state.value.phase == CallPhase.IDLE && b.calls.state.value.phase == CallPhase.IDLE }
        eventually(what = "resources released") { a.engine.allReleased() && b.engine.allReleased() }
        assertEquals(EndReason.HANGUP_REMOTE, finished.single().endReason)
        assertTrue(a.media.active.value == null && b.media.active.value == null)
    }

    @Test
    fun `declined and busy calls`() {
        val a = device("A", 1000); val b = device("B", 2000); val c = device("C", 3000)
        trustEachOther(a, b, c)
        started(a, b, c)
        a.connectTo(b); c.connectTo(b)
        eventually { a.node.isReachable(b.id) && c.node.isReachable(b.id) }

        assertTrue(a.calls.call(b.id))
        eventually { b.calls.state.value.phase == CallPhase.INCOMING_RINGING }
        b.calls.reject()
        eventually(what = "declined") { a.calls.state.value.endReason == EndReason.DECLINED_REMOTE }
        eventually { a.calls.state.value.phase == CallPhase.IDLE && b.calls.state.value.phase == CallPhase.IDLE }

        assertTrue(a.calls.call(b.id))
        eventually { b.calls.state.value.phase == CallPhase.INCOMING_RINGING }
        b.calls.accept()
        eventually { b.calls.state.value.phase == CallPhase.ACTIVE }
        assertTrue(c.calls.call(b.id))
        eventually(what = "busy") { c.calls.state.value.endReason == EndReason.BUSY }
        assertEquals(CallPhase.ACTIVE, b.calls.state.value.phase)
        a.calls.hangup()
    }

    @Test
    fun `call through a relay carries audio both ways`() {
        val a = device("A", 1000); val b = device("B", 2000); val c = device("C", 3000)
        trustEachOther(a, b, c)
        started(a, b, c)
        a.connectTo(b); c.connectTo(b)
        eventually { a.node.isReachable(c.id) && c.node.isReachable(a.id) }
        assertTrue(a.calls.call(c.id))
        eventually { c.calls.state.value.phase == CallPhase.INCOMING_RINGING }
        c.calls.accept()
        eventually(what = "relayed audio") { c.engine.heardCount(1000) > 10 && a.engine.heardCount(3000) > 10 }
        assertEquals(0, b.engine.capturesOpened.get(), "the relay never opens its microphone")
        a.calls.hangup()
        eventually { a.engine.allReleased() && c.engine.allReleased() }
    }

    @Test
    fun `call fails cleanly when the other phone disappears`() {
        val a = device("A", 1000); val b = device("B", 2000)
        trustEachOther(a, b)
        started(a, b)
        a.connectTo(b)
        eventually { a.node.isReachable(b.id) }
        a.calls.call(b.id)
        eventually { b.calls.state.value.phase == CallPhase.INCOMING_RINGING }
        b.calls.accept()
        eventually { a.calls.state.value.phase == CallPhase.ACTIVE }
        val phases = Collections.synchronizedList(ArrayList<CallPhase>())
        a.scope.launch { a.calls.state.collect { phases += it.phase } }
        b.stop()
        eventually(timeoutMs = 10_000, what = "connection lost") { a.calls.state.value.endReason == EndReason.CONNECTION_LOST }
        assertTrue(CallPhase.RECONNECTING in phases)
        eventually { a.engine.allReleased() }
    }

    // ------------------------------------------------------------------ groups and push-to-talk

    private fun groupOf3(mode: GroupMode, meshed: Boolean = false): Triple<TestDevice, TestDevice, TestDevice> {
        val a = device("A", 1000); val b = device("B", 2000); val c = device("C", 3000)
        trustEachOther(a, b, c)
        started(a, b, c)
        b.connectTo(a); c.connectTo(a)
        if (meshed) b.connectTo(c)
        eventually { a.node.isReachable(b.id) && a.node.isReachable(c.id) && b.node.isReachable(c.id) }
        assertTrue(runBlocking { a.groups.create("Team", mode, listOf(b.id, c.id)) }.isSuccess)
        eventually(what = "invites") { b.groups.invites.value.isNotEmpty() && c.groups.invites.value.isNotEmpty() }
        runBlocking {
            b.groups.acceptInvite(b.groups.invites.value.single().groupId).getOrThrow()
            c.groups.acceptInvite(c.groups.invites.value.single().groupId).getOrThrow()
        }
        eventually(what = "all joined") {
            listOf(a, b, c).all { d -> d.groups.group.value?.let { it.status == GroupStatus.ACTIVE && it.members.size == 3 } == true } &&
                a.groups.group.value!!.epoch == b.groups.group.value!!.epoch && a.groups.group.value!!.epoch == c.groups.group.value!!.epoch
        }
        return Triple(a, b, c)
    }

    @Test
    fun `push-to-talk floor control allows one talker and recovers from a lost holder`() {
        val (a, b, c) = groupOf3(GroupMode.PTT)
        runBlocking { b.groups.pressTalk() }
        eventually(what = "B talking") { b.groups.ptt.value.talking && c.groups.ptt.value.holder == b.id && a.groups.ptt.value.holder == b.id }
        runBlocking { c.groups.pressTalk() }
        eventually(what = "C denied") { c.groups.ptt.value.denied && !c.groups.ptt.value.talking }
        eventually(what = "C hears B through the host") { c.engine.heardCount(2000) > 10 && a.engine.heardCount(2000) > 10 }
        assertEquals(0L, b.engine.heardCount(3000), "non-holder audio is never sent")

        runBlocking { b.groups.releaseTalk() }
        eventually(what = "floor free") { c.groups.ptt.value.holder == null && a.groups.ptt.value.holder == null }
        runBlocking { c.groups.pressTalk() }
        eventually(what = "C talking") { c.groups.ptt.value.talking && b.groups.ptt.value.holder == c.id }
        eventually { a.engine.heardCount(3000) > 5 }
        runBlocking { c.groups.releaseTalk() }

        // Holder disappears while talking: the floor must not stay locked.
        eventually { a.groups.ptt.value.holder == null }
        runBlocking { b.groups.pressTalk() }
        eventually { a.groups.ptt.value.holder == b.id }
        b.stop()
        eventually(timeoutMs = 10_000, what = "floor revoked") { a.groups.ptt.value.holder == null && c.groups.ptt.value.holder == null }
        runBlocking { c.groups.pressTalk() }
        eventually { c.groups.ptt.value.talking }
    }

    @Test
    fun `group call mixes members and kicking rotates the key`() {
        val (a, b, c) = groupOf3(GroupMode.CALL)
        eventually(what = "everyone hears everyone") {
            b.engine.heardCount(1000 + 3000) + b.engine.heardCount(1000) > 10 &&
                a.engine.heardCount(2000 + 3000) + a.engine.heardCount(2000) > 10
        }
        val epoch = a.groups.group.value!!.epoch
        val ended = Collections.synchronizedList(ArrayList<GroupEvent>())
        c.scope.launch { c.groups.events.collect { if (it is GroupEvent.Ended) ended += it } }
        runBlocking { a.groups.kick(c.id) }
        eventually(what = "C removed") { c.groups.group.value == null && ended.isNotEmpty() }
        eventually(what = "new epoch") { a.groups.group.value!!.epoch > epoch && b.groups.group.value!!.epoch == a.groups.group.value!!.epoch }
        assertEquals(2, b.groups.group.value!!.members.size)
        eventually { c.engine.allReleased() }
        runBlocking { b.groups.leave() }
        eventually { a.groups.group.value!!.members.size == 1 && b.groups.group.value == null }
        runBlocking { a.groups.leave() }
        eventually { a.engine.allReleased() && b.engine.allReleased() }
    }

    @Test
    fun `next member takes over when the host disappears`() {
        val (a, b, c) = groupOf3(GroupMode.CALL, meshed = true)
        a.stop()
        eventually(timeoutMs = 15_000, what = "B hosts") {
            b.groups.group.value?.role == GroupRole.HOST && c.groups.group.value?.host == b.id && c.groups.group.value?.status == GroupStatus.ACTIVE
        }
        assertEquals(2, b.groups.group.value!!.members.size)
        eventually(what = "audio after recovery") { c.engine.heardCount(2000) > 5 }
    }

    @Test
    fun `group text goes through the host to every member once`() {
        val (a, b, c) = groupOf3(GroupMode.PTT)
        runBlocking { b.messages.sendToGroup("hi team") }
        eventually(what = "everyone has it") { a.store.all.any { it.body == "hi team" } && c.store.all.any { it.body == "hi team" && it.author == b.id } }
        Thread.sleep(1_000)
        assertEquals(1, c.store.all.count { it.body == "hi team" })
        val g = runBlocking { withTimeout(1_000) { a.groups.group.first { it != null } } }
        assertNotNull(g)
    }
}
