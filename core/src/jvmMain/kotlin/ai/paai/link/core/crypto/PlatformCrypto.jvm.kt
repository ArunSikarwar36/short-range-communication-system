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

/** Android / desktop: Bouncy Castle lightweight API. */
internal actual object PlatformCrypto {
    actual fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(privateKey, 0))
        val out = ByteArray(32)
        try {
            agreement.calculateAgreement(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            // Bouncy Castle rejects low-order points that would yield an all-zero secret.
            throw CryptoException("key agreement failed")
        }
        return out
    }

    actual fun x25519Base(privateKey: ByteArray): ByteArray =
        X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded

    actual fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(true, AEADParameters(KeyParameter(key), 128, nonce, ad))
        val out = ByteArray(c.getOutputSize(plaintext.size))
        val n = c.processBytes(plaintext, 0, plaintext.size, out, 0)
        c.doFinal(out, n)
        return out
    }

    actual fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(false, AEADParameters(KeyParameter(key), 128, nonce, ad))
        val out = ByteArray(c.getOutputSize(ciphertext.size))
        try {
            val n = c.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            c.doFinal(out, n)
        } catch (e: InvalidCipherTextException) {
            throw CryptoException("authentication failed")
        }
        return out
    }

    actual fun sha256(parts: Array<out ByteArray>): ByteArray {
        val d = SHA256Digest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(32).also { d.doFinal(it, 0) }
    }

    actual fun hmacSha256(key: ByteArray, parts: Array<out ByteArray>): ByteArray {
        val m = HMac(SHA256Digest())
        m.init(KeyParameter(key))
        for (p in parts) m.update(p, 0, p.size)
        return ByteArray(32).also { m.doFinal(it, 0) }
    }
}
