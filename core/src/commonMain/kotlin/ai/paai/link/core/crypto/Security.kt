package ai.paai.link.core.crypto

import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.withLock
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.toHex

/** The device's long-term identity: a static X25519 key pair plus a user-chosen name. */
class LocalIdentity(val keyPair: KeyPair, val displayName: String) {
    val deviceId: DeviceId = DeviceId.fromPublicKey(keyPair.publicKey)
    val fingerprint: String get() = Fingerprint.of(keyPair.publicKey)
}

object Fingerprint {
    /** Human-comparable fingerprint of a public key, e.g. "3f2a 91c0 77de 0b14 e5a2". */
    fun of(publicKey: ByteArray): String =
        Primitives.sha256(publicKey).copyOf(10).toHex().chunked(4).joinToString(" ")
}

/**
 * Short authentication string for pairing. Derived from the Noise XX handshake hash, which both
 * devices compute identically only if no one intercepted the key exchange. Users compare the
 * six digits on both screens before confirming.
 */
object Sas {
    private val LABEL = "SRCS pairing code v1".encodeToByteArray()

    fun code(handshakeHash: ByteArray): String {
        val mac = Primitives.hmac(handshakeHash, LABEL)
        val v = ((mac[0].toLong() and 0xff) shl 24) or ((mac[1].toLong() and 0xff) shl 16) or
            ((mac[2].toLong() and 0xff) shl 8) or (mac[3].toLong() and 0xff)
        val digits = (v % 1_000_000).toString().padStart(6, '0')
        return digits.substring(0, 3) + " " + digits.substring(3)
    }
}

/**
 * End-to-end sealing between paired devices using the one-way Noise K pattern: the sender
 * authenticates with its static key, the recipient's static key is already known from pairing,
 * and a fresh ephemeral key is used per message. Relays forward sealed messages without being
 * able to read or modify them. The routing header is bound in as the Noise prologue, so a relay
 * cannot alter sender, recipient, type or message ID either.
 */
object Sealer {
    /** Bytes added by sealing: ephemeral public key + AEAD tag. */
    const val OVERHEAD = Primitives.DH_LEN + Primitives.TAG_LEN

    fun seal(local: KeyPair, recipientStatic: ByteArray, boundHeader: ByteArray, payload: ByteArray): ByteArray =
        HandshakeState(NoisePattern.K, initiator = true, prologue = boundHeader, s = local, rs = recipientStatic)
            .writeMessage(payload)

    fun open(local: KeyPair, senderStatic: ByteArray, boundHeader: ByteArray, sealed: ByteArray): ByteArray {
        if (sealed.size < OVERHEAD) throw CryptoException("sealed message too short")
        return HandshakeState(NoisePattern.K, initiator = false, prologue = boundHeader, s = local, rs = senderStatic)
            .readMessage(sealed)
    }
}

/**
 * Authenticated encryption for UDP voice packets. Packets can arrive out of order, so the
 * nonce is explicit: [stream id (4) | sequence (4)], unique because each sender in a session is
 * assigned a distinct stream id and sequence numbers never repeat within a stream.
 * A fresh random key is created for every call / group epoch, so nonces are never reused across keys.
 */
class MediaCipher(key: ByteArray) {
    private val key = key.copyOf()

    init {
        require(key.size == Primitives.KEY_LEN)
    }

    fun encrypt(streamId: Int, seq: Long, aad: ByteArray, plaintext: ByteArray): ByteArray =
        Primitives.aeadEncrypt(key, nonce(streamId, seq), aad, plaintext)

    fun decrypt(streamId: Int, seq: Long, aad: ByteArray, ciphertext: ByteArray): ByteArray =
        Primitives.aeadDecrypt(key, nonce(streamId, seq), aad, ciphertext)

    private fun nonce(streamId: Int, seq: Long): ByteArray {
        require(seq in 0..0xffffffffL)
        val n = ByteArray(12)
        for (i in 0 until 4) n[4 + i] = (streamId ushr (24 - 8 * i)).toByte()
        for (i in 0 until 4) n[8 + i] = (seq ushr (24 - 8 * i)).toByte()
        return n
    }

    companion object {
        fun newKey(): ByteArray = Primitives.randomBytes(Primitives.KEY_LEN)
    }
}

/**
 * Sliding anti-replay window (in the style of RFC 4303 / RFC 6479). Accepts each sequence
 * number at most once and rejects numbers older than the window.
 */
class ReplayWindow(private val size: Int = 256) {
    private val lock = Lock()
    private val bits = LongArray((size + 63) / 64)
    private var highest = -1L

    init {
        require(size > 0 && size % 64 == 0)
    }

    /** Returns true if [seq] is new and records it. */
    fun accept(seq: Long): Boolean {
        return lock.withLock {
            if (seq < 0) return false
            if (seq > highest) {
                val shift = seq - highest
                if (shift >= size) bits.fill(0) else repeat(shift.toInt()) { shiftOne() }
                highest = seq
                set(0)
                return true
            }
            val offset = highest - seq
            if (offset >= size) return false
            if (get(offset.toInt())) return false
            set(offset.toInt())
            return true
        }
    }

    /** Check without recording, so that authentication can happen before the window moves. */
    fun wouldAccept(seq: Long): Boolean {
        return lock.withLock {
            if (seq < 0) return false
            if (seq > highest) return true
            val offset = highest - seq
            return offset < size && !get(offset.toInt())
        }
    }

    private fun get(i: Int) = (bits[i / 64] ushr (i % 64)) and 1L == 1L
    private fun set(i: Int) { bits[i / 64] = bits[i / 64] or (1L shl (i % 64)) }
    private fun shiftOne() {
        // Shift the whole bitmap left by one (bit i -> bit i+1).
        for (w in bits.indices.reversed()) {
            val carry = if (w > 0) bits[w - 1] ushr 63 else 0L
            bits[w] = (bits[w] shl 1) or carry
        }
    }
}
