package ai.paai.link.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/** Authentication or key-agreement failure. Never carries key material in its message. */
class CryptoException(message: String) : Exception(message)

/**
 * Cryptographic primitives, all from the Bouncy Castle lightweight API:
 * X25519, ChaCha20-Poly1305 (RFC 8439) and SHA-256 / HMAC-SHA-256.
 * No primitive is implemented by hand.
 */
object Primitives {
    const val DH_LEN = 32
    const val KEY_LEN = 32
    const val HASH_LEN = 32
    const val TAG_LEN = 16
    private val random = SecureRandom()

    fun dh(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        if (publicKey.size != DH_LEN) throw CryptoException("bad public key length")
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(DH_LEN)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            // Bouncy Castle rejects low-order points that would yield an all-zero secret.
            throw CryptoException("key agreement failed")
        }
        return out
    }

    fun generateKeyPair(): KeyPair {
        val priv = X25519PrivateKeyParameters(random)
        return KeyPair(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun publicFromPrivate(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    /** ChaCha20-Poly1305 with a 96-bit nonce. Output = ciphertext || 16-byte tag. */
    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), TAG_LEN * 8, nonce, ad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < TAG_LEN) throw CryptoException("ciphertext too short")
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), TAG_LEN * 8, nonce, ad))
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, n)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("authentication failed")
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = SHA256Digest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(HASH_LEN).also { d.doFinal(it, 0) }
    }

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val m = HMac(SHA256Digest())
        m.init(KeyParameter(key))
        for (p in parts) m.update(p, 0, p.size)
        return ByteArray(HASH_LEN).also { m.doFinal(it, 0) }
    }

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
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

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
