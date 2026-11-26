package ai.paai.link.core

import ai.paai.link.core.crypto.CryptoException
import ai.paai.link.core.crypto.PortableCrypto
import ai.paai.link.core.crypto.Primitives
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

/**
 * Differential test: the pure-Kotlin primitives used on iOS must agree with Bouncy Castle
 * (used on Android) on random inputs of many sizes, including keys and public points with
 * arbitrary bits set.
 */
class PortableCryptoTest {
    private val rnd = Random(20261126)

    @Test
    fun sha256AndHmacMatchBouncyCastle() {
        for (len in (0..300) + listOf(511, 512, 513, 4096, 70_000)) {
            val m = rnd.nextBytes(len)
            assertContentEquals(Primitives.sha256(m), PortableCrypto.sha256(m), "sha256 len $len")
            val k = rnd.nextBytes(len % 140)
            assertContentEquals(Primitives.hmac(k, m), PortableCrypto.hmacSha256(k, m), "hmac key ${k.size} len $len")
            val a = m.copyOf(len / 3); val b = m.copyOfRange(len / 3, len)
            assertContentEquals(Primitives.sha256(a, b), PortableCrypto.sha256(a, b), "multi-part sha256 len $len")
        }
    }

    @Test
    fun aeadMatchesBouncyCastle() {
        for (len in (0..300) + listOf(1023, 1024, 1025, 65_000)) {
            val key = rnd.nextBytes(32); val nonce = rnd.nextBytes(12); val ad = rnd.nextBytes(rnd.nextInt(0, 70)); val pt = rnd.nextBytes(len)
            val bc = Primitives.aeadEncrypt(key, nonce, ad, pt)
            assertContentEquals(bc, PortableCrypto.aeadEncrypt(key, nonce, ad, pt), "seal len $len")
            assertContentEquals(pt, PortableCrypto.aeadDecrypt(key, nonce, ad, bc), "open len $len")
            val bad = bc.copyOf().also { val i = rnd.nextInt(it.size); it[i] = (it[i].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
            assertFailsWith<CryptoException> { PortableCrypto.aeadDecrypt(key, nonce, ad, bad) }
        }
    }

    @Test
    fun poly1305EdgeCasesMatchBouncyCastle() {
        // Tags whose accumulator lands near 2^130 - 5 exercise the final reduction.
        val key = ByteArray(32) { 0xff.toByte() }
        for (len in listOf(0, 1, 15, 16, 17, 32, 64, 255)) {
            val pt = ByteArray(len) { 0xff.toByte() }
            val nonce = ByteArray(12)
            assertContentEquals(Primitives.aeadEncrypt(key, nonce, pt, pt), PortableCrypto.aeadEncrypt(key, nonce, pt, pt), "len $len")
        }
    }

    @Test
    fun x25519MatchesBouncyCastle() {
        repeat(300) {
            val a = rnd.nextBytes(32); val b = rnd.nextBytes(32)
            val pubA = Primitives.publicFromPrivate(a)
            assertContentEquals(pubA, PortableCrypto.x25519Base(a), "base")
            // Arbitrary 32-byte strings as the public point, including the high bit set.
            val u = rnd.nextBytes(32)
            val bc = runCatching { Primitives.dh(b, u) }
            val portable = runCatching { PortableCrypto.x25519(b, u) }
            if (bc.isSuccess) assertContentEquals(bc.getOrThrow(), portable.getOrThrow(), "dh") else assertFailsWith<CryptoException> { portable.getOrThrow() }
            assertContentEquals(Primitives.dh(b, pubA), PortableCrypto.x25519(b, pubA), "dh with valid key")
        }
    }
}
