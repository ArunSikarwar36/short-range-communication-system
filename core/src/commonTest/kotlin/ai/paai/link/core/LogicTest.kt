package ai.paai.link.core

import ai.paai.link.core.call.CallAction
import ai.paai.link.core.call.CallEvent
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.call.CallState
import ai.paai.link.core.call.CallStateMachine
import ai.paai.link.core.call.CallTimer
import ai.paai.link.core.call.EndReason
import ai.paai.link.core.media.BitrateController
import ai.paai.link.core.media.JitterBuffer
import ai.paai.link.core.protocol.RejectReason
import ai.paai.link.core.protocol.RouteEntry
import ai.paai.link.core.ptt.FloorArbiter
import ai.paai.link.core.routing.DedupeCache
import ai.paai.link.core.routing.RouteTable
import ai.paai.link.core.util.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallStateMachineTest {
    private val peer = DeviceId.random()
    private val callId = DeviceId.random()

    private fun run(vararg events: CallEvent): Pair<CallState, List<CallAction>> {
        var s = CallState()
        val all = ArrayList<CallAction>()
        for (e in events) {
            val (n, a) = CallStateMachine.reduce(s, e)
            s = n
            all += a
        }
        return s to all
    }

    @Test
    fun `outgoing call happy path`() {
        val (s, actions) = run(
            CallEvent.PlaceCall(peer, callId, 0),
            CallEvent.RemoteRinging(callId),
            CallEvent.RemoteAccept(callId),
            CallEvent.MediaFlowing(100),
        )
        assertEquals(CallPhase.ACTIVE, s.phase)
        assertEquals(100, s.activeSince)
        assertTrue(CallAction.SendInvite in actions)
        assertTrue(CallAction.StartMedia in actions)
    }

    @Test
    fun `incoming call accept then local hangup goes through ENDING to ENDED`() {
        var (s, _) = run(CallEvent.RemoteInvite(peer, callId, 0), CallEvent.LocalAccept, CallEvent.MediaFlowing(5))
        assertEquals(CallPhase.ACTIVE, s.phase)
        val (ending, a1) = CallStateMachine.reduce(s, CallEvent.LocalHangup)
        assertEquals(CallPhase.ENDING, ending.phase)
        assertTrue(CallAction.SendEnd in a1 && CallAction.StopMedia in a1)
        val (ended, a2) = CallStateMachine.reduce(ending, CallEvent.MediaReleased)
        assertEquals(CallPhase.ENDED, ended.phase)
        assertEquals(EndReason.HANGUP_LOCAL, ended.endReason)
        assertTrue(a2.any { it is CallAction.Finished })
        s = CallStateMachine.reduce(ended, CallEvent.Timeout(CallTimer.LINGER)).first
        assertEquals(CallPhase.IDLE, s.phase)
    }

    @Test
    fun `caller sees declined busy no-answer and unreachable`() {
        assertEquals(EndReason.DECLINED_REMOTE, run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.RemoteRinging(callId), CallEvent.RemoteReject(callId, RejectReason.DECLINED)).first.endReason)
        assertEquals(EndReason.BUSY, run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.RemoteBusy(callId)).first.endReason)
        val (na, a) = run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.RemoteRinging(callId), CallEvent.Timeout(CallTimer.RING))
        assertEquals(EndReason.NO_ANSWER, na.endReason)
        assertTrue(CallAction.SendCancel in a)
        assertEquals(EndReason.UNREACHABLE, run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.SendFailed).first.endReason)
        assertEquals(CallPhase.FAILED, run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.Timeout(CallTimer.INVITE)).first.phase)
    }

    @Test
    fun `callee sees missed call when caller cancels or ring times out`() {
        assertEquals(EndReason.MISSED, run(CallEvent.RemoteInvite(peer, callId, 0), CallEvent.RemoteCancel(callId)).first.endReason)
        assertEquals(EndReason.MISSED, run(CallEvent.RemoteInvite(peer, callId, 0), CallEvent.Timeout(CallTimer.RING)).first.endReason)
        val (rej, a) = run(CallEvent.RemoteInvite(peer, callId, 0), CallEvent.LocalReject)
        assertEquals(EndReason.DECLINED_LOCAL, rej.endReason)
        assertTrue(a.any { it is CallAction.SendReject })
    }

    @Test
    fun `second invite while in a call gets busy and does not disturb the call`() {
        val (s, _) = run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.RemoteAccept(callId), CallEvent.MediaFlowing(1))
        val other = DeviceId.random()
        val (s2, a) = CallStateMachine.reduce(s, CallEvent.RemoteInvite(other, DeviceId.random(), 2))
        assertEquals(s, s2)
        assertIs<CallAction.SendBusy>(a.single())
    }

    @Test
    fun `stall reconnect and loss`() {
        val (active, _) = run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.RemoteAccept(callId), CallEvent.MediaFlowing(1))
        val (rec, _) = CallStateMachine.reduce(active, CallEvent.MediaStalled)
        assertEquals(CallPhase.RECONNECTING, rec.phase)
        assertEquals(CallPhase.ACTIVE, CallStateMachine.reduce(rec, CallEvent.MediaFlowing(9)).first.phase)
        val (failed, a) = CallStateMachine.reduce(rec, CallEvent.Timeout(CallTimer.RECONNECT))
        assertEquals(CallPhase.FAILED, failed.phase)
        assertEquals(EndReason.CONNECTION_LOST, failed.endReason)
        assertTrue(CallAction.StopMedia in a)
    }

    @Test
    fun `messages for another call id are ignored`() {
        val (s, _) = run(CallEvent.PlaceCall(peer, callId, 0))
        assertEquals(s, CallStateMachine.reduce(s, CallEvent.RemoteAccept(DeviceId.random())).first)
        assertEquals(s, CallStateMachine.reduce(s, CallEvent.RemoteEnd(DeviceId.random())).first)
    }

    @Test
    fun `stale timers do nothing`() {
        val (s, _) = run(CallEvent.PlaceCall(peer, callId, 0), CallEvent.RemoteAccept(callId), CallEvent.MediaFlowing(1))
        for (t in CallTimer.entries) if (t != CallTimer.LINGER) assertEquals(s, CallStateMachine.reduce(s, CallEvent.Timeout(t)).first)
    }
}

class FloorArbiterTest {
    private val a = DeviceId.random()
    private val b = DeviceId.random()

    @Test
    fun `only one talker at a time`() {
        val f = FloorArbiter()
        val g = f.request(a, 0)
        assertIs<FloorArbiter.Decision.Granted>(g)
        assertEquals(FloorArbiter.Decision.Denied(a), f.request(b, 10))
        assertFalse(f.release(b, g.floorSeq))
        assertFalse(f.release(a, g.floorSeq + 1)) // stale sequence
        assertTrue(f.release(a, g.floorSeq))
        val g2 = f.request(b, 20)
        assertIs<FloorArbiter.Decision.Granted>(g2)
        assertTrue(g2.floorSeq > g.floorSeq)
    }

    @Test
    fun `a silent holder is revoked so the channel cannot be locked`() {
        val f = FloorArbiter(maxTalkMs = 60_000, keepaliveTimeoutMs = 3_000)
        val g = f.request(a, 0) as FloorArbiter.Decision.Granted
        f.keepalive(a, g.floorSeq, 2_000)
        assertNull(f.tick(4_000))
        assertEquals(a, f.tick(5_500))
        assertNull(f.holder)
        assertIs<FloorArbiter.Decision.Granted>(f.request(b, 5_600))
    }

    @Test
    fun `maximum talk time and member departure free the floor`() {
        val f = FloorArbiter(maxTalkMs = 10_000, keepaliveTimeoutMs = 3_000)
        val g = f.request(a, 0) as FloorArbiter.Decision.Granted
        for (t in 1_000L..10_000L step 1_000) f.keepalive(a, g.floorSeq, t)
        assertEquals(a, f.tick(10_500))
        f.request(b, 11_000)
        assertTrue(f.memberLeft(b))
        assertNull(f.holder)
    }
}

class RoutingTest {
    private val a = DeviceId.random()
    private val b = DeviceId.random()
    private val c = DeviceId.random()
    private val d = DeviceId.random()

    @Test
    fun `routes are learned across a line topology`() {
        // a - b - c - d
        val tb = RouteTable(b); val tc = RouteTable(c); val ta = RouteTable(a)
        tb.addNeighbor(c, "C", 0); tc.addNeighbor(d, "D", 0); ta.addNeighbor(b, "B", 0)
        tc.tick(); tb.tick()
        tb.onAdvert(c, tc.advertFor(b, "C", true), 0)
        assertEquals(c, tb.nextHop(d)); assertEquals(2, tb.route(d)!!.hops)
        ta.onAdvert(b, tb.advertFor(a, "B", true), 0)
        assertEquals(b, ta.nextHop(d)); assertEquals(3, ta.route(d)!!.hops)
        assertEquals(b, ta.nextHop(c))
    }

    @Test
    fun `split horizon and relay policy`() {
        val tb = RouteTable(b)
        tb.addNeighbor(a, "A", 0); tb.addNeighbor(c, "C", 0)
        val toA = tb.advertFor(a, "B", true).map { it.dest }
        assertTrue(c in toA); assertFalse(a in toA)
        val noRelay = tb.advertFor(a, "B", relayEnabled = false).map { it.dest }
        assertEquals(listOf(b), noRelay)
    }

    @Test
    fun `fresher sequence numbers win and loops are avoided`() {
        val t = RouteTable(a)
        t.addNeighbor(b, "B", 0); t.addNeighbor(c, "C", 0)
        t.onAdvert(b, listOf(RouteEntry(d, 10, 1, "D")), 0)
        assertEquals(b, t.nextHop(d))
        // Same freshness but longer: ignored.
        t.onAdvert(c, listOf(RouteEntry(d, 10, 3, "D")), 0)
        assertEquals(b, t.nextHop(d))
        // Same freshness, shorter: taken.
        t.onAdvert(c, listOf(RouteEntry(d, 10, 0, "D")), 0)
        assertEquals(c, t.nextHop(d))
        // Older information is ignored even if shorter.
        t.onAdvert(b, listOf(RouteEntry(d, 8, 0, "D")), 0)
        assertEquals(c, t.nextHop(d))
        // Our own ID is never routed.
        t.onAdvert(b, listOf(RouteEntry(a, 99, 0, "A")), 0)
        assertNull(t.route(a))
    }

    @Test
    fun `losing a neighbour withdraws its routes and the withdrawal propagates`() {
        val ta = RouteTable(a)
        ta.addNeighbor(b, "B", 0)
        ta.onAdvert(b, listOf(RouteEntry(c, 4, 1, "C")), 0)
        assertEquals(listOf(b, c).toSet(), ta.removeNeighbor(b).toSet())
        assertNull(ta.nextHop(c))
        // Neighbour x that learned c via a receives the withdrawal (odd seq, infinite hops).
        val x = DeviceId.random()
        val tx = RouteTable(x)
        tx.addNeighbor(a, "A", 0)
        tx.onAdvert(a, listOf(RouteEntry(c, 4, 2, "C")), 0)
        assertEquals(a, tx.nextHop(c))
        val withdrawal = ta.advertFor(x, "A", true).first { it.dest == c }
        assertTrue(withdrawal.broken)
        tx.onAdvert(a, listOf(withdrawal), 1)
        assertNull(tx.nextHop(c))
    }

    @Test
    fun `stale routes expire but direct neighbours do not`() {
        val t = RouteTable(a, routeTimeoutMs = 1_000)
        t.addNeighbor(b, "B", 0)
        t.onAdvert(b, listOf(RouteEntry(c, 2, 1, "C")), 0)
        assertEquals(listOf(c), t.expire(5_000, setOf(b)))
        assertEquals(b, t.nextHop(b))
    }

    @Test
    fun `hop limit is enforced`() {
        val t = RouteTable(a)
        t.addNeighbor(b, "B", 0)
        t.onAdvert(b, listOf(RouteEntry(c, 2, 6, "C")), 0)
        assertNull(t.route(c))
    }

    @Test
    fun `dedupe cache suppresses duplicates within ttl`() {
        val cache = DedupeCache(capacity = 3, ttlMs = 1_000)
        val id = DeviceId.random()
        assertFalse(cache.checkAndAdd(id, 0))
        assertTrue(cache.checkAndAdd(id, 500))
        assertFalse(cache.checkAndAdd(id, 2_000)) // expired, treated as new
        repeat(5) { cache.checkAndAdd(DeviceId.random(), 2_001) }
        assertFalse(cache.contains(id, 2_002)) // evicted by capacity
    }
}

class JitterBufferTest {
    private fun pkt(i: Int) = byteArrayOf(i.toByte())

    @Test
    fun `buffers to target delay then plays in order with concealment for gaps`() {
        val jb = JitterBuffer(frameMs = 20, minDelayMs = 40)
        jb.put(0, 0, 0, pkt(0))
        assertEquals(JitterBuffer.Frame.Empty, jb.poll())
        jb.put(2, 40, 40, pkt(2)) // seq 1 lost
        jb.put(3, 60, 60, pkt(3))
        val f0 = jb.poll(); assertIs<JitterBuffer.Frame.Packet>(f0); assertEquals(0, f0.payload[0].toInt())
        assertEquals(JitterBuffer.Frame.Missing, jb.poll())
        assertEquals(2, (jb.poll() as JitterBuffer.Frame.Packet).payload[0].toInt())
        assertEquals(1, jb.lost)
    }

    @Test
    fun `late and duplicate packets are dropped`() {
        val jb = JitterBuffer(frameMs = 20, minDelayMs = 40)
        for (i in 0..3) jb.put(i.toLong(), i * 20L, i * 20L, pkt(i))
        jb.poll(); jb.poll()
        jb.put(0, 0, 100, pkt(0))
        assertEquals(1, jb.late)
        jb.put(3, 60, 100, pkt(3))
        assertEquals(1, jb.duplicates)
    }

    @Test
    fun `latency is bounded by skipping old audio`() {
        val jb = JitterBuffer(frameMs = 20, minDelayMs = 40, maxDelayMs = 200, maxExtraMs = 100, capacity = 64)
        for (i in 0 until 40) jb.put(i.toLong(), i * 20L, 0, pkt(i))
        jb.poll()
        assertTrue(jb.bufferedMs <= jb.targetDelayMs + 100)
        assertTrue(jb.skipped > 0)
    }

    @Test
    fun `stall re-buffers instead of concealing forever`() {
        val jb = JitterBuffer(frameMs = 20, minDelayMs = 40)
        for (i in 0..2) jb.put(i.toLong(), i * 20L, i * 20L, pkt(i))
        repeat(3) { jb.poll() }
        var empties = 0
        repeat(20) { if (jb.poll() == JitterBuffer.Frame.Empty) empties++ }
        assertTrue(empties > 0)
        assertFalse(jb.isPlaying)
    }

    @Test
    fun `jitter raises the target delay`() {
        val jb = JitterBuffer(frameMs = 20, minDelayMs = 40, maxDelayMs = 200)
        val rnd = kotlin.random.Random(3)
        for (i in 0 until 200) jb.put(i.toLong(), i * 20L, i * 20L + rnd.nextLong(0, 80), pkt(i))
        assertTrue(jb.jitterMs > 10)
        assertTrue(jb.targetDelayMs > 40)
    }

    @Test
    fun `bitrate steps down on loss and up after a good period`() {
        val b = BitrateController(initial = 24_000)
        assertEquals(16_000, b.onReport("x", 150, 0))
        assertEquals(12_000, b.onReport("x", 150, 2_000))
        assertNull(b.onReport("x", 5, 4_000))
        assertEquals(16_000, b.onReport("x", 5, 14_000))
    }
}
