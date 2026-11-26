package ai.paai.link.core.protocol

import ai.paai.link.core.util.ByteReader
import ai.paai.link.core.util.ByteWriter
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.ProtocolException
import ai.paai.link.core.transport.TcpConnection
import ai.paai.link.core.transport.readFully

object ProtocolLimits {
    const val VERSION = 1
    /** Noise caps a transport message at 65535 bytes, tag included. */
    const val MAX_FRAME = 65535
    const val MAX_NAME_BYTES = 64
    const val MAX_TEXT_BYTES = 4000
    const val MAX_ROUTES_PER_ADVERT = 64
    const val MAX_GROUP_MEMBERS = 8
    const val MAX_HOPS = 6
    const val MAX_MEDIA_PAYLOAD = 1200
}

/** Length-prefixed (u16 big-endian) frames on a TCP stream. */
object FrameIO {
    fun write(out: TcpConnection, frame: ByteArray) {
        if (frame.isEmpty() || frame.size > ProtocolLimits.MAX_FRAME) throw ProtocolException("bad frame size ${frame.size}")
        out.write(byteArrayOf((frame.size ushr 8).toByte(), frame.size.toByte()))
        out.write(frame)
        out.flush()
    }

    /** Returns null on clean end of stream. */
    fun read(input: TcpConnection): ByteArray? {
        val header = ByteArray(2)
        if (!input.readFully(header, eofAllowed = true)) return null
        val len = ((header[0].toInt() and 0xff) shl 8) or (header[1].toInt() and 0xff)
        if (len == 0) throw ProtocolException("empty frame")
        val b = ByteArray(len)
        input.readFully(b)
        return b
    }
}

/** Sent inside the encrypted Noise XX handshake payloads. */
data class LinkHello(
    val protocolVersion: Int,
    val displayName: String,
    val udpPort: Int,
    val relayCapable: Boolean,
) {
    fun encode(): ByteArray = ByteWriter()
        .u8(protocolVersion).string(displayName, ProtocolLimits.MAX_NAME_BYTES).u16(udpPort).bool(relayCapable).bytes()

    companion object {
        fun decode(b: ByteArray): LinkHello {
            val r = ByteReader(b)
            val h = LinkHello(r.u8(), r.string(ProtocolLimits.MAX_NAME_BYTES), r.u16(), r.bool())
            r.requireEnd()
            if (h.protocolVersion != ProtocolLimits.VERSION) throw ProtocolException("unsupported protocol version ${h.protocolVersion}")
            return h
        }
    }
}

enum class EnvelopeType(val code: Int) {
    PAIR_CONFIRM(1), PAIR_REJECT(2), PING(3), PONG(4), ROUTE_ADVERT(5), SEALED(6), CLOSE(7);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: throw ProtocolException("unknown envelope type $code")
    }
}

/**
 * Routing envelope carried inside an encrypted link frame. [ttl] is the only field relays change;
 * everything else is bound to the end-to-end seal through [boundHeader].
 */
class Envelope(
    val type: EnvelopeType,
    val ttl: Int,
    val flags: Int,
    val messageId: DeviceId,
    val src: DeviceId,
    val dst: DeviceId,
    val body: ByteArray,
) {
    val storeAndForward: Boolean get() = flags and FLAG_STORE_FORWARD != 0

    fun boundHeader(): ByteArray = ByteWriter()
        .u8(ProtocolLimits.VERSION).u8(type.code).u8(flags).id(messageId).id(src).id(dst).bytes()

    fun encode(): ByteArray = ByteWriter(body.size + 64)
        .u8(ProtocolLimits.VERSION).u8(type.code).u8(ttl).u8(flags).id(messageId).id(src).id(dst)
        .blob(body, ProtocolLimits.MAX_FRAME - 128).bytes()

    fun withTtl(newTtl: Int) = Envelope(type, newTtl, flags, messageId, src, dst, body)

    companion object {
        const val FLAG_STORE_FORWARD = 1

        fun decode(b: ByteArray): Envelope {
            val r = ByteReader(b)
            val v = r.u8()
            if (v != ProtocolLimits.VERSION) throw ProtocolException("unsupported envelope version $v")
            val type = EnvelopeType.of(r.u8())
            val ttl = r.u8()
            val flags = r.u8()
            if (ttl > ProtocolLimits.MAX_HOPS) throw ProtocolException("ttl too large")
            val e = Envelope(type, ttl, flags, r.id(), r.id(), r.id(), r.blob(ProtocolLimits.MAX_FRAME - 128))
            r.requireEnd()
            return e
        }
    }
}

/** One destination in a distance-vector advertisement (DSDV-style sequence numbers). */
data class RouteEntry(val dest: DeviceId, val seq: Long, val hops: Int, val name: String) {
    val broken: Boolean get() = hops == INFINITE

    companion object {
        const val INFINITE = 255
    }
}

data class RouteAdvert(val relayCapable: Boolean, val entries: List<RouteEntry>) {
    fun encode(): ByteArray {
        require(entries.size <= ProtocolLimits.MAX_ROUTES_PER_ADVERT)
        val w = ByteWriter().bool(relayCapable).u8(entries.size)
        for (e in entries) w.id(e.dest).u32(e.seq).u8(e.hops).string(e.name, ProtocolLimits.MAX_NAME_BYTES)
        return w.bytes()
    }

    companion object {
        fun decode(b: ByteArray): RouteAdvert {
            val r = ByteReader(b)
            val relay = r.bool()
            val n = r.u8()
            if (n > ProtocolLimits.MAX_ROUTES_PER_ADVERT) throw ProtocolException("too many routes")
            val list = List(n) { RouteEntry(r.id(), r.u32(), r.u8(), r.string(ProtocolLimits.MAX_NAME_BYTES)) }
            r.requireEnd()
            return RouteAdvert(relay, list)
        }
    }
}
