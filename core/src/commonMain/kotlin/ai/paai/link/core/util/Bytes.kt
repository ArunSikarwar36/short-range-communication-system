package ai.paai.link.core.util

import ai.paai.link.core.crypto.Primitives

/** Thrown for any malformed, oversized or unexpected wire data. */
class ProtocolException(message: String) : Exception(message)

/**
 * Stable application identity of a device: the first 16 bytes of SHA-256 over its
 * static X25519 public key. It is bound to the key, so it cannot be claimed without
 * the private key, and it is never derived from a Wi-Fi hardware address.
 */
class DeviceId(bytes: ByteArray) : Comparable<DeviceId> {
    private val raw: ByteArray = bytes.copyOf()

    init {
        require(bytes.size == SIZE) { "DeviceId must be $SIZE bytes" }
    }

    fun toBytes(): ByteArray = raw.copyOf()
    val hex: String get() = raw.toHex()
    /** Short form for logs and UI secondary text. */
    val short: String get() = hex.take(8)
    val isZero: Boolean get() = raw.all { it == 0.toByte() }

    override fun equals(other: Any?): Boolean = other is DeviceId && raw.contentEquals(other.raw)
    override fun hashCode(): Int = raw.contentHashCode()
    override fun toString(): String = short
    override fun compareTo(other: DeviceId): Int = hex.compareTo(other.hex)

    companion object {
        const val SIZE = 16
        val ZERO = DeviceId(ByteArray(SIZE))

        fun fromPublicKey(publicKey: ByteArray): DeviceId =
            DeviceId(Primitives.sha256(publicKey).copyOf(SIZE))

        fun fromHex(hex: String): DeviceId = DeviceId(hex.hexToBytes())

        fun random(): DeviceId = DeviceId(Random.bytes(SIZE))
    }
}

/** 16-byte random identifier used for messages, calls and groups. */
typealias Id16 = DeviceId

object Random {
    fun bytes(n: Int): ByteArray = secureRandomBytes(n)
    fun int(): Int = bytes(4).let { ((it[0].toInt() and 0xff) shl 24) or ((it[1].toInt() and 0xff) shl 16) or ((it[2].toInt() and 0xff) shl 8) or (it[3].toInt() and 0xff) }
}

fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
    }
    return sb.toString()
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

private const val HEX = "0123456789abcdef"

/** Big-endian binary writer with bounded strings and blobs. */
class ByteWriter(initial: Int = 64) {
    private val out = GrowableBytes(initial)

    fun u8(v: Int) = apply { out.write(v and 0xff) }
    fun u16(v: Int) = apply { out.write((v ushr 8) and 0xff); out.write(v and 0xff) }
    fun u32(v: Long) = apply { for (s in 24 downTo 0 step 8) out.write(((v ushr s) and 0xff).toInt()) }
    fun i32(v: Int) = u32(v.toLong() and 0xffffffffL)
    fun u64(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write(((v ushr s) and 0xff).toInt()) }
    fun bool(v: Boolean) = u8(if (v) 1 else 0)
    fun raw(b: ByteArray) = apply { out.write(b) }
    fun id(d: DeviceId) = raw(d.toBytes())
    fun fixed(b: ByteArray, size: Int) = apply {
        require(b.size == size) { "expected $size bytes, got ${b.size}" }
        out.write(b)
    }

    fun blob(b: ByteArray, max: Int = 0xffff) = apply {
        require(b.size <= max) { "blob too large: ${b.size} > $max" }
        u16(b.size); out.write(b)
    }

    fun string(s: String, maxBytes: Int) = blob(s.encodeToByteArray(), maxBytes)

    fun bytes(): ByteArray = out.toByteArray()
    val size: Int get() = out.size()
}

/** Big-endian reader that throws [ProtocolException] on any underflow or bound violation. */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    val remaining: Int get() = end - pos

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw ProtocolException("truncated: need $n, have $remaining")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xff }
    fun u16(): Int { need(2); return ((buf[pos++].toInt() and 0xff) shl 8) or (buf[pos++].toInt() and 0xff) }
    fun u32(): Long {
        need(4)
        var v = 0L
        repeat(4) { v = (v shl 8) or (buf[pos++].toLong() and 0xff) }
        return v
    }
    fun i32(): Int = u32().toInt()
    fun u64(): Long {
        need(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (buf[pos++].toLong() and 0xff) }
        return v
    }
    fun bool(): Boolean = when (u8()) {
        0 -> false
        1 -> true
        else -> throw ProtocolException("invalid boolean")
    }
    fun fixed(n: Int): ByteArray { need(n); return buf.copyOfRange(pos, pos + n).also { pos += n } }
    fun id(): DeviceId = DeviceId(fixed(DeviceId.SIZE))
    fun blob(max: Int = 0xffff): ByteArray {
        val n = u16()
        if (n > max) throw ProtocolException("blob too large: $n > $max")
        return fixed(n)
    }
    fun string(maxBytes: Int): String {
        val b = blob(maxBytes)
        // Reject invalid UTF-8 rather than silently substituting characters.
        val s = try {
            b.decodeToString(0, b.size, throwOnInvalidSequence = true)
        } catch (e: CharacterCodingException) {
            throw ProtocolException("invalid UTF-8")
        }
        if (!s.encodeToByteArray().contentEquals(b)) throw ProtocolException("invalid UTF-8")
        return s
    }
    fun rest(): ByteArray = fixed(remaining)
    fun requireEnd() {
        if (remaining != 0) throw ProtocolException("$remaining trailing bytes")
    }
}

/** Minimal growable byte buffer (replacement for java.io.ByteArrayOutputStream). */
class GrowableBytes(initial: Int = 64) {
    private var buf = ByteArray(maxOf(initial, 16))
    private var count = 0

    private fun ensure(extra: Int) {
        if (count + extra > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, count + extra))
    }

    fun write(b: Int) { ensure(1); buf[count++] = b.toByte() }
    fun write(b: ByteArray) { ensure(b.size); b.copyInto(buf, count); count += b.size }
    fun size(): Int = count
    fun toByteArray(): ByteArray = buf.copyOf(count)
}
