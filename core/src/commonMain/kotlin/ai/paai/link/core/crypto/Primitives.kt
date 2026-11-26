package ai.paai.link.core.crypto

import ai.paai.link.core.util.secureRandomBytes

/** Authentication or key-agreement failure. Never carries key material in its message. */
class CryptoException(message: String) : Exception(message)

/**
 * The four primitives SRCS needs. JVM/Android: Bouncy Castle. iOS: [PortableCrypto], which is
 * tested against Bouncy Castle and the RFC / Noise test vectors on the JVM.
 */
internal expect object PlatformCrypto {
    /** X25519; throws [CryptoException] if the result is all zero (low-order public key). */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray
    fun x25519Base(privateKey: ByteArray): ByteArray
    /** ChaCha20-Poly1305 (RFC 8439). Output = ciphertext || 16-byte tag. */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray
    /** Throws [CryptoException] when the tag does not verify. */
    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray
    fun sha256(parts: Array<out ByteArray>): ByteArray
    fun hmacSha256(key: ByteArray, parts: Array<out ByteArray>): ByteArray
}

/** Cryptographic building blocks: X25519, ChaCha20-Poly1305 (RFC 8439), SHA-256 / HMAC-SHA-256, HKDF. */
object Primitives {
    const val DH_LEN = 32
    const val KEY_LEN = 32
    const val HASH_LEN = 32
    const val TAG_LEN = 16

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (privateKey.size != DH_LEN) throw CryptoException("bad private key length")
        if (publicKey.size != DH_LEN) throw CryptoException("bad public key length")
        return PlatformCrypto.x25519(privateKey, publicKey)
    }

    fun generateKeyPair(): KeyPair {
        val priv = secureRandomBytes(DH_LEN)
        return KeyPair(priv, PlatformCrypto.x25519Base(priv))
    }

    fun publicFromPrivate(privateKey: ByteArray): ByteArray = PlatformCrypto.x25519Base(privateKey)

    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        require(key.size == KEY_LEN && nonce.size == 12)
        return PlatformCrypto.aeadEncrypt(key, nonce, ad, plaintext)
    }

    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(key.size == KEY_LEN && nonce.size == 12)
        if (ciphertext.size < TAG_LEN) throw CryptoException("ciphertext too short")
        return PlatformCrypto.aeadDecrypt(key, nonce, ad, ciphertext)
    }

    fun sha256(vararg parts: ByteArray): ByteArray = PlatformCrypto.sha256(parts)

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray = PlatformCrypto.hmacSha256(key, parts)

    /** The HKDF construction used by the Noise framework (section 4.3). */
    fun noiseHkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
        val temp = hmac(chainingKey, ikm)
        val o1 = hmac(temp, byteArrayOf(1))
        val o2 = hmac(temp, o1, byteArrayOf(2))
        if (outputs == 2) return listOf(o1, o2)
        val o3 = hmac(temp, o2, byteArrayOf(3))
        return listOf(o1, o2, o3)
    }

    /** RFC 5869 HKDF-SHA-256 for deriving independent sub-keys with a label. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun randomBytes(n: Int): ByteArray = secureRandomBytes(n)

    /** Constant-time comparison. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }
}

class KeyPair(privateKey: ByteArray, publicKey: ByteArray) {
    private val priv = privateKey.copyOf()
    val publicKey: ByteArray = publicKey.copyOf()

    init {
        require(priv.size == Primitives.DH_LEN && publicKey.size == Primitives.DH_LEN)
    }

    fun privateKeyCopy(): ByteArray = priv.copyOf()
    internal fun dh(remotePublic: ByteArray): ByteArray = Primitives.dh(priv, remotePublic)

    override fun toString(): String = "KeyPair(public only shown)"

    companion object {
        fun fromPrivate(privateKey: ByteArray): KeyPair = KeyPair(privateKey, Primitives.publicFromPrivate(privateKey))
    }
}
