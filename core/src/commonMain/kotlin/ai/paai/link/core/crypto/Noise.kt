package ai.paai.link.core.crypto

/**
 * Implementation of the Noise Protocol Framework (revision 34, https://noiseprotocol.org/noise.html)
 * restricted to DH=25519, cipher=ChaChaPoly, hash=SHA256 and the two patterns SRCS uses:
 *
 *  - XX: mutual authentication between directly linked devices (link layer).
 *  - K:  one-way sealed messages between already-paired devices (end-to-end layer, works across relays).
 *
 * The state machine follows the specification's CipherState / SymmetricState / HandshakeState objects
 * literally and is checked against the published cacophony test vectors (see NoiseVectorsTest).
 */
class CipherState(private var key: ByteArray? = null) {
    private var nonce: Long = 0

    val hasKey: Boolean get() = key != null

    fun initializeKey(k: ByteArray?) {
        key = k?.copyOf()
        nonce = 0
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val k = key ?: return plaintext
        checkNonce()
        val ct = Primitives.aeadEncrypt(k, nonceBytes(nonce), ad, plaintext)
        nonce++
        return ct
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val k = key ?: return ciphertext
        checkNonce()
        val pt = Primitives.aeadDecrypt(k, nonceBytes(nonce), ad, ciphertext)
        nonce++
        return pt
    }

    private fun checkNonce() {
        // 2^64-1 is reserved by the spec; a session never gets near it, but fail closed anyway.
        if (nonce == -1L) throw CryptoException("nonce exhausted")
    }

    /** Raw key, needed only to derive independent media keys after the handshake. */
    internal fun keyCopy(): ByteArray = key?.copyOf() ?: throw IllegalStateException("no key")

    companion object {
        /** Noise ChaChaPoly nonce: 32 zero bits followed by the 64-bit counter, little-endian. */
        fun nonceBytes(n: Long): ByteArray {
            val b = ByteArray(12)
            for (i in 0 until 8) b[4 + i] = ((n ushr (8 * i)) and 0xff).toByte()
            return b
        }
    }
}

class SymmetricState(protocolName: String) {
    private var ck: ByteArray
    var h: ByteArray
        private set
    private val cipher = CipherState()

    init {
        val name = protocolName.encodeToByteArray()
        h = if (name.size <= Primitives.HASH_LEN) name.copyOf(Primitives.HASH_LEN) else Primitives.sha256(name)
        ck = h.copyOf()
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = Primitives.noiseHkdf(ck, ikm, 2)
        ck = newCk
        cipher.initializeKey(tempK)
    }

    fun mixHash(data: ByteArray) {
        h = Primitives.sha256(h, data)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    val cipherHasKey: Boolean get() = cipher.hasKey

    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = Primitives.noiseHkdf(ck, ByteArray(0), 2)
        return CipherState(k1) to CipherState(k2)
    }
}

enum class Token { E, S, EE, ES, SE, SS }

enum class NoisePattern(
    val protocolName: String,
    val initiatorPreMessage: List<Token>,
    val responderPreMessage: List<Token>,
    val messages: List<List<Token>>,
) {
    XX(
        "Noise_XX_25519_ChaChaPoly_SHA256",
        emptyList(), emptyList(),
        listOf(listOf(Token.E), listOf(Token.E, Token.EE, Token.S, Token.ES), listOf(Token.S, Token.SE)),
    ),
    K(
        "Noise_K_25519_ChaChaPoly_SHA256",
        listOf(Token.S), listOf(Token.S),
        listOf(listOf(Token.E, Token.ES, Token.SS)),
    ),
}

/** Result of a completed handshake. */
class HandshakeResult(
    val send: CipherState,
    val receive: CipherState,
    /** Channel-binding value identical on both sides; used for the pairing verification code. */
    val handshakeHash: ByteArray,
    val remoteStatic: ByteArray,
)

class HandshakeState(
    private val pattern: NoisePattern,
    private val initiator: Boolean,
    prologue: ByteArray,
    private val s: KeyPair,
    private var rs: ByteArray? = null,
    /** Fixed ephemeral key, for test vectors only. */
    private val fixedEphemeral: KeyPair? = null,
) {
    private val ss = SymmetricState(pattern.protocolName)
    private var e: KeyPair? = null
    private var re: ByteArray? = null
    private var index = 0
    private var result: HandshakeResult? = null

    init {
        ss.mixHash(prologue)
        // Pre-messages: initiator's first, then responder's.
        for (t in pattern.initiatorPreMessage) {
            check(t == Token.S)
            ss.mixHash(if (initiator) s.publicKey else requireNotNull(rs) { "remote static required" })
        }
        for (t in pattern.responderPreMessage) {
            check(t == Token.S)
            ss.mixHash(if (initiator) requireNotNull(rs) { "remote static required" } else s.publicKey)
        }
    }

    val isComplete: Boolean get() = result != null
    val isMyTurn: Boolean get() = (index % 2 == 0) == initiator

    fun writeMessage(payload: ByteArray): ByteArray {
        check(!isComplete && isMyTurn) { "not our turn to write" }
        val out = ai.paai.link.core.util.GrowableBytes()
        for (t in pattern.messages[index]) {
            when (t) {
                Token.E -> {
                    val eph = fixedEphemeral ?: Primitives.generateKeyPair()
                    e = eph
                    out.write(eph.publicKey)
                    ss.mixHash(eph.publicKey)
                }
                Token.S -> out.write(ss.encryptAndHash(s.publicKey))
                else -> ss.mixKey(dhFor(t))
            }
        }
        out.write(ss.encryptAndHash(payload))
        advance()
        return out.toByteArray()
    }

    fun readMessage(message: ByteArray): ByteArray {
        check(!isComplete && !isMyTurn) { "not our turn to read" }
        var pos = 0
        fun take(n: Int): ByteArray {
            if (message.size - pos < n) throw CryptoException("handshake message truncated")
            return message.copyOfRange(pos, pos + n).also { pos += n }
        }
        for (t in pattern.messages[index]) {
            when (t) {
                Token.E -> {
                    val r = take(Primitives.DH_LEN)
                    re = r
                    ss.mixHash(r)
                }
                Token.S -> {
                    val len = if (ss.cipherHasKey) Primitives.DH_LEN + Primitives.TAG_LEN else Primitives.DH_LEN
                    rs = ss.decryptAndHash(take(len))
                }
                else -> ss.mixKey(dhFor(t))
            }
        }
        val payload = ss.decryptAndHash(message.copyOfRange(pos, message.size))
        advance()
        return payload
    }

    fun result(): HandshakeResult = result ?: throw IllegalStateException("handshake incomplete")

    private fun dhFor(t: Token): ByteArray {
        val eph = e
        val remoteE = re
        val remoteS = rs
        return when (t) {
            Token.EE -> requireNotNull(eph).dh(requireNotNull(remoteE))
            Token.ES -> if (initiator) requireNotNull(eph).dh(requireNotNull(remoteS)) else s.dh(requireNotNull(remoteE))
            Token.SE -> if (initiator) s.dh(requireNotNull(remoteE)) else requireNotNull(eph).dh(requireNotNull(remoteS))
            Token.SS -> s.dh(requireNotNull(remoteS))
            else -> error("not a DH token")
        }
    }

    private fun advance() {
        index++
        if (index == pattern.messages.size) {
            val (c1, c2) = ss.split()
            val remote = rs ?: throw CryptoException("remote static unknown")
            result = if (initiator) HandshakeResult(c1, c2, ss.h.copyOf(), remote)
            else HandshakeResult(c2, c1, ss.h.copyOf(), remote)
        }
    }
}
