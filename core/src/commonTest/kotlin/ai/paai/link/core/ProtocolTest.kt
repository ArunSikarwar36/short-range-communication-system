package ai.paai.link.core

import ai.paai.link.core.protocol.AckStatus
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.protocol.Envelope
import ai.paai.link.core.protocol.EnvelopeType
import ai.paai.link.core.protocol.GroupMember
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.protocol.LinkHello
import ai.paai.link.core.protocol.MediaKind
import ai.paai.link.core.protocol.MediaPacket
import ai.paai.link.core.protocol.RejectReason
import ai.paai.link.core.protocol.RouteAdvert
import ai.paai.link.core.protocol.RouteEntry
import ai.paai.link.core.protocol.SealedPayload
import ai.paai.link.core.util.ByteWriter
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.ProtocolException
import kotlin.test.Test
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail

class ProtocolTest {
    private fun id() = DeviceId.random()
    private val key = ByteArray(32) { it.toByte() }

    private val samples: List<AppMessage> = listOf(
        AppMessage.CallInvite(id(), key, CodecConfig()),
        AppMessage.CallRinging(id()),
        AppMessage.CallAccept(id()),
        AppMessage.CallReject(id(), RejectReason.BUSY),
        AppMessage.CallCancel(id()),
        AppMessage.CallEnd(id()),
        AppMessage.CallBusy(id()),
        AppMessage.CallKeepalive(id()),
        AppMessage.PttRequest(id()),
        AppMessage.PttGranted(id(), 5, 60_000),
        AppMessage.PttDenied(id(), id()),
        AppMessage.PttRelease(id(), 5),
        AppMessage.PttKeepalive(id(), 5),
        AppMessage.PttRevoked(id(), 5),
        AppMessage.PttFloorState(id(), DeviceId.ZERO, 6),
        AppMessage.GroupInvite(id(), "Team ☀", GroupMode.PTT, CodecConfig(bitrate = 16_000)),
        AppMessage.GroupJoin(id()),
        AppMessage.GroupDecline(id()),
        AppMessage.GroupLeave(id()),
        AppMessage.GroupKick(id()),
        AppMessage.GroupUpdate(id(), 3, "Team", GroupMode.CALL, CodecConfig(), key, listOf(GroupMember(id(), "A", 1), GroupMember(id(), "Bé", 2))),
        AppMessage.GroupEnd(id()),
        AppMessage.Text(id(), DeviceId.ZERO, id(), "Arun", 1_700_000_000_000, "नमस्ते, hello 👋"),
        AppMessage.TextAck(id(), AckStatus.READ),
        AppMessage.MediaReport(id(), 25, 12),
    )

    @Test
    fun `every app message round-trips`() {
        for (m in samples) {
            val decoded = AppMessage.decode(m.encode())
            assertEquals(m, decoded, "round trip of ${m::class.simpleName}")
        }
        val (t, m) = SealedPayload.decode(SealedPayload.encode(123, samples[0]))
        assertEquals(123, t)
        assertEquals(samples[0], m)
    }

    @Test
    fun `truncated messages are rejected with ProtocolException only`() {
        for (m in samples) {
            val b = m.encode()
            for (n in 0 until b.size) {
                try {
                    AppMessage.decode(b.copyOf(n))
                    fail("prefix $n of ${m::class.simpleName} decoded")
                } catch (e: ProtocolException) {
                    // expected
                }
            }
            assertFailsWith<ProtocolException> { AppMessage.decode(b + 0) } // trailing byte
        }
    }

    @Test
    fun `random bytes never crash the decoders`() {
        val rnd = Random(1234)
        repeat(20_000) {
            val b = rnd.nextBytes(rnd.nextInt(0, 200))
            if (b.isNotEmpty()) b[0] = rnd.nextInt(0, 45).toByte() // aim at real message codes
            for (decode in listOf<(ByteArray) -> Any>(
                { AppMessage.decode(it) }, { Envelope.decode(it) }, { RouteAdvert.decode(it) },
                { LinkHello.decode(it) }, { MediaPacket.decode(it) },
            )) {
                try {
                    decode(b)
                } catch (e: ProtocolException) {
                    // fine
                } catch (e: Exception) {
                    fail("decoder threw ${e::class.simpleName} for ${b.size} bytes: ${e.message}")
                }
            }
        }
    }

    @Test
    fun `mutated valid messages never crash the decoder`() {
        val rnd = Random(99)
        for (m in samples) {
            val b = m.encode()
            repeat(500) {
                val c = b.copyOf()
                c[rnd.nextInt(c.size)] = rnd.nextInt(256).toByte()
                try { AppMessage.decode(c) } catch (e: ProtocolException) { } catch (e: Exception) {
                    fail("${m::class.simpleName} mutation threw ${e::class.simpleName}")
                }
            }
        }
    }

    @Test
    fun `bounds are enforced`() {
        assertFailsWith<ProtocolException> { CodecConfig(44_100, 20, 24_000).validate() }
        assertFailsWith<ProtocolException> { CodecConfig(48_000, 25, 24_000).validate() }
        val tooMany = ByteWriter().u8(25).id(id()).u32(1).string("x", 64).u8(1).u32(48_000).u8(20).u32(24_000).raw(key).u8(9).bytes()
        assertFailsWith<ProtocolException> { AppMessage.decode(tooMany) }
        val dupMember = id()
        val dup = AppMessage.GroupUpdate(id(), 1, "x", GroupMode.CALL, CodecConfig(), key, listOf(GroupMember(dupMember, "a", 1), GroupMember(dupMember, "b", 2)))
        assertFailsWith<ProtocolException> { AppMessage.decode(dup.encode()) }
        val invalidUtf8 = ByteWriter().u8(2).id(id()).bytes() // CallRinging is fine…
        AppMessage.decode(invalidUtf8)
        val badText = ByteWriter().u8(30).id(id()).id(id()).id(id()).blob(byteArrayOf(0xC3.toByte())).u64(0).string("x", 10).bytes()
        assertFailsWith<ProtocolException> { AppMessage.decode(badText) }
    }

    @Test
    fun `envelope round trip and bound header excludes ttl`() {
        val e = Envelope(EnvelopeType.SEALED, 5, Envelope.FLAG_STORE_FORWARD, id(), id(), id(), byteArrayOf(1, 2, 3))
        val d = Envelope.decode(e.encode())
        assertEquals(e.type, d.type); assertEquals(5, d.ttl); assertEquals(e.messageId, d.messageId)
        assertContentEquals(e.body, d.body)
        assertContentEquals(e.boundHeader(), d.withTtl(1).boundHeader())
        assertEquals(true, d.storeAndForward)
        val bad = e.encode().also { it[0] = 9 }
        assertFailsWith<ProtocolException> { Envelope.decode(bad) }
        val ttl = e.encode().also { it[2] = 50 }
        assertFailsWith<ProtocolException> { Envelope.decode(ttl) }
    }

    @Test
    fun `media packet aad is stable under re-addressing`() {
        val p = MediaPacket(MediaKind.VOICE, 6, id(), id(), id(), 3, 77, 960, ByteArray(40) { 1 })
        val d = MediaPacket.decode(p.encode())
        assertEquals(77, d.seq); assertEquals(3, d.streamId); assertEquals(960, d.timestamp)
        assertContentEquals(p.aad(), d.readdressed(id(), 2).aad())
        assertFailsWith<ProtocolException> { MediaPacket.decode(ByteArray(10)) }
        assertFailsWith<ProtocolException> { MediaPacket.decode(ByteArray(2000)) }
    }

    @Test
    fun `route advert and hello round trip`() {
        val a = RouteAdvert(true, listOf(RouteEntry(id(), 4, 1, "B"), RouteEntry(id(), 7, RouteEntry.INFINITE, "C")))
        assertEquals(a, RouteAdvert.decode(a.encode()))
        val h = LinkHello(1, "Pixel", 47701, true)
        assertEquals(h, LinkHello.decode(h.encode()))
        assertFailsWith<ProtocolException> { LinkHello.decode(LinkHello(2, "x", 1, false).encode()) }
    }
}
