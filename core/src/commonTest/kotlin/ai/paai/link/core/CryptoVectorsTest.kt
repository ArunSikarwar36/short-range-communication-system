package ai.paai.link.core

import ai.paai.link.core.crypto.CryptoException
import ai.paai.link.core.crypto.PortableCrypto
import ai.paai.link.core.crypto.Primitives
import ai.paai.link.core.util.hexToBytes
import ai.paai.link.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Published test vectors, checked against both the platform primitives ([Primitives]: Bouncy Castle
 * on the JVM, [PortableCrypto] on iOS) and [PortableCrypto] directly.
 */
class CryptoVectorsTest {
    private class Impl(
        val name: String,
        val sha: (ByteArray) -> ByteArray,
        val hmac: (ByteArray, ByteArray) -> ByteArray,
        val x25519: (ByteArray, ByteArray) -> ByteArray,
        val base: (ByteArray) -> ByteArray,
        val seal: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
        val open: (ByteArray, ByteArray, ByteArray, ByteArray) -> ByteArray,
    )

    private val impls = listOf(
        Impl("platform", { Primitives.sha256(it) }, { k, m -> Primitives.hmac(k, m) }, Primitives::dh, Primitives::publicFromPrivate,
            Primitives::aeadEncrypt, Primitives::aeadDecrypt),
        Impl("portable", { PortableCrypto.sha256(it) }, { k, m -> PortableCrypto.hmacSha256(k, m) }, PortableCrypto::x25519,
            PortableCrypto::x25519Base, PortableCrypto::aeadEncrypt, PortableCrypto::aeadDecrypt),
    )

    private fun ascii(s: String) = s.encodeToByteArray()

    @Test
    fun sha256Fips180Vectors() {
        for (i in impls) {
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", i.sha(ByteArray(0)).toHex(), i.name)
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", i.sha(ascii("abc")).toHex(), i.name)
            assertEquals(
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
                i.sha(ascii("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")).toHex(), i.name,
            )
            assertEquals("cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0", i.sha(ByteArray(1_000_000) { 'a'.code.toByte() }).toHex(), i.name)
        }
    }

    @Test
    fun hmacRfc4231Vectors() {
        for (i in impls) {
            assertEquals(
                "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
                i.hmac(ascii("Jefe"), ascii("what do ya want for nothing?")).toHex(), i.name,
            )
            assertEquals(
                "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
                i.hmac(ByteArray(131) { 0xaa.toByte() }, ascii("Test Using Larger Than Block-Size Key - Hash Key First")).toHex(), i.name,
            )
        }
    }

    @Test
    fun x25519Rfc7748Vectors() {
        val alicePriv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexToBytes()
        val bobPriv = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb".hexToBytes()
        for (i in impls) {
            val alicePub = i.base(alicePriv)
            val bobPub = i.base(bobPriv)
            assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alicePub.toHex(), i.name)
            assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bobPub.toHex(), i.name)
            val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
            assertEquals(shared, i.x25519(alicePriv, bobPub).toHex(), i.name)
            assertEquals(shared, i.x25519(bobPriv, alicePub).toHex(), i.name)
            assertEquals(
                "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
                i.x25519(
                    "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4".hexToBytes(),
                    "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c".hexToBytes(),
                ).toHex(),
                i.name,
            )
            // Iterated test (section 5.2): k = u = 9, then k <- X25519(k, u), u <- old k.
            var k = ByteArray(32).also { it[0] = 9 }
            var u = k.copyOf()
            repeat(1) { val r = i.x25519(k, u); u = k; k = r }
            assertEquals("422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079", k.toHex(), i.name)
            repeat(999) { val r = i.x25519(k, u); u = k; k = r }
            assertEquals("684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51", k.toHex(), i.name)
            // A low-order public key (zero) must be rejected rather than yield an all-zero secret.
            assertFailsWith<CryptoException>(i.name) { i.x25519(alicePriv, ByteArray(32)) }
        }
    }

    @Test
    fun poly1305Rfc8439Vector() {
        val tag = PortableCrypto.poly1305(
            "85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b".hexToBytes(),
            ascii("Cryptographic Forum Research Group"),
        )
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", tag.toHex())
    }

    @Test
    fun chacha20Poly1305Rfc8439Vector() {
        val key = "808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f".hexToBytes()
        val nonce = "070000004041424344454647".hexToBytes()
        val ad = "50515253c0c1c2c3c4c5c6c7".hexToBytes()
        val pt = ascii("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.")
        val expected = "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
            "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc3ff4def08e4b7a9de576d265" +
            "86cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691"
        for (i in impls) {
            val ct = i.seal(key, nonce, ad, pt)
            assertEquals(expected, ct.toHex(), i.name)
            assertEquals(pt.toHex(), i.open(key, nonce, ad, ct).toHex(), i.name)
            val bad = ct.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
            assertFailsWith<CryptoException>(i.name) { i.open(key, nonce, ad, bad) }
            assertFailsWith<CryptoException>(i.name) { i.open(key, nonce, ad + 0, ct) }
        }
    }
}
