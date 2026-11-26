package ai.paai.link.core

import ai.paai.link.core.crypto.CryptoException
import ai.paai.link.core.crypto.HandshakeState
import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.MediaCipher
import ai.paai.link.core.crypto.NoisePattern
import ai.paai.link.core.crypto.Primitives
import ai.paai.link.core.crypto.ReplayWindow
import ai.paai.link.core.crypto.Sas
import ai.paai.link.core.crypto.Sealer
import ai.paai.link.core.util.hexToBytes
import ai.paai.link.core.util.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NoiseVectorsTest {
    private class Vector(val protocol: String, val fields: Map<String, String>, val messages: List<Pair<ByteArray, ByteArray>>)

    private fun load(): List<Vector> {
        val lines = NOISE_VECTORS.lines()
        val out = ArrayList<Vector>()
        var proto = ""
        var fields = HashMap<String, String>()
        var msgs = ArrayList<Pair<ByteArray, ByteArray>>()
        for (l in lines) {
            if (l.startsWith("#") || l.isBlank()) continue
            val parts = l.split(" ")
            when (parts[0]) {
                "protocol" -> { proto = parts[1]; fields = HashMap(); msgs = ArrayList() }
                "message" -> msgs += (if (parts[1] == "-") ByteArray(0) else parts[1].hexToBytes()) to parts[2].hexToBytes()
                "end" -> out += Vector(proto, fields, msgs)
                else -> fields[parts[0]] = parts[1]
            }
        }
        return out
    }

    private fun kp(hex: String) = KeyPair.fromPrivate(hex.hexToBytes())

    @Test
    fun `XX and K handshakes and transport match cacophony vectors`() {
        val vectors = load()
        assertEquals(setOf("Noise_XX_25519_ChaChaPoly_SHA256", "Noise_K_25519_ChaChaPoly_SHA256"), vectors.map { it.protocol }.toSet())
        for (v in vectors) {
            val pattern = if (v.protocol.contains("_XX_")) NoisePattern.XX else NoisePattern.K
            val f = v.fields
            val iStatic = kp(f.getValue("init_static"))
            val rStatic = kp(f.getValue("resp_static"))
            val init = HandshakeState(
                pattern, true, f.getValue("init_prologue").hexToBytes(), iStatic,
                rs = f["init_remote_static"]?.hexToBytes(), fixedEphemeral = kp(f.getValue("init_ephemeral")),
            )
            val resp = HandshakeState(
                pattern, false, f.getValue("resp_prologue").hexToBytes(), rStatic,
                rs = f["resp_remote_static"]?.hexToBytes(), fixedEphemeral = f["resp_ephemeral"]?.let { kp(it) },
            )
            val hsCount = pattern.messages.size
            v.messages.forEachIndexed { i, (payload, ciphertext) ->
                if (i < hsCount) {
                    val (w, r) = if (i % 2 == 0) init to resp else resp to init
                    assertEquals(ciphertext.toHex(), w.writeMessage(payload).toHex(), "${v.protocol} handshake message $i")
                    assertContentEquals(payload, r.readMessage(ciphertext))
                    if (i == hsCount - 1) {
                        assertEquals(f.getValue("handshake_hash"), init.result().handshakeHash.toHex())
                        assertEquals(f.getValue("handshake_hash"), resp.result().handshakeHash.toHex())
                    }
                } else {
                    val fromInitiator = pattern == NoisePattern.K || i % 2 == 0
                    val (w, r) = if (fromInitiator) init.result() to resp.result() else resp.result() to init.result()
                    assertEquals(ciphertext.toHex(), w.send.encryptWithAd(ByteArray(0), payload).toHex(), "${v.protocol} transport $i")
                    assertContentEquals(payload, r.receive.decryptWithAd(ByteArray(0), ciphertext))
                }
            }
        }
    }
}

class CryptoTest {
    private val alice = Primitives.generateKeyPair()
    private val bob = Primitives.generateKeyPair()
    private val mallory = Primitives.generateKeyPair()

    @Test
    fun `sealed message opens only for the right recipient and sender`() {
        val header = "header".encodeToByteArray()
        val sealed = Sealer.seal(alice, bob.publicKey, header, "hello".encodeToByteArray())
        assertEquals("hello", Sealer.open(bob, alice.publicKey, header, sealed).decodeToString())
        assertFailsWith<CryptoException> { Sealer.open(bob, mallory.publicKey, header, sealed) }
        assertFailsWith<CryptoException> { Sealer.open(mallory, alice.publicKey, header, sealed) }
    }

    @Test
    fun `sealed message rejects a modified header or body`() {
        val header = "header".encodeToByteArray()
        val sealed = Sealer.seal(alice, bob.publicKey, header, "hello".encodeToByteArray())
        assertFailsWith<CryptoException> { Sealer.open(bob, alice.publicKey, "headex".encodeToByteArray(), sealed) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertFailsWith<CryptoException> { Sealer.open(bob, alice.publicKey, header, tampered) }
        assertFailsWith<CryptoException> { Sealer.open(bob, alice.publicKey, header, sealed.copyOf(10)) }
    }

    @Test
    fun `two seals of the same payload differ because each uses a fresh ephemeral key`() {
        val a = Sealer.seal(alice, bob.publicKey, ByteArray(0), "x".encodeToByteArray())
        val b = Sealer.seal(alice, bob.publicKey, ByteArray(0), "x".encodeToByteArray())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `media cipher authenticates header and payload`() {
        val key = MediaCipher.newKey()
        val c = MediaCipher(key)
        val aad = "hdr".encodeToByteArray()
        val ct = c.encrypt(7, 42, aad, "voice".encodeToByteArray())
        assertEquals("voice", MediaCipher(key).decrypt(7, 42, aad, ct).decodeToString())
        assertFailsWith<CryptoException> { c.decrypt(7, 43, aad, ct) } // wrong nonce
        assertFailsWith<CryptoException> { c.decrypt(8, 42, aad, ct) }
        assertFailsWith<CryptoException> { c.decrypt(7, 42, "hdX".encodeToByteArray(), ct) }
        assertFailsWith<CryptoException> { MediaCipher(MediaCipher.newKey()).decrypt(7, 42, aad, ct) }
    }

    @Test
    fun `replay window accepts each sequence once and rejects old ones`() {
        val w = ReplayWindow(128)
        assertTrue(w.accept(10))
        assertFalse(w.accept(10))
        assertTrue(w.accept(12))
        assertTrue(w.accept(11)) // out of order but inside window
        assertFalse(w.accept(11))
        assertTrue(w.accept(500))
        assertFalse(w.accept(500 - 128)) // fell out of the window
        assertTrue(w.accept(500 - 127))
        assertFalse(w.wouldAccept(500))
        assertTrue(w.wouldAccept(501))
        assertFalse(w.accept(-1))
    }

    @Test
    fun `pairing code is six digits and identical for both sides of a handshake`() {
        val i = HandshakeState(NoisePattern.XX, true, ByteArray(0), alice)
        val r = HandshakeState(NoisePattern.XX, false, ByteArray(0), bob)
        r.readMessage(i.writeMessage(ByteArray(0)))
        i.readMessage(r.writeMessage(ByteArray(0)))
        r.readMessage(i.writeMessage(ByteArray(0)))
        val a = Sas.code(i.result().handshakeHash)
        assertEquals(a, Sas.code(r.result().handshakeHash))
        assertTrue(Regex("\\d{3} \\d{3}").matches(a))
        assertContentEquals(bob.publicKey, i.result().remoteStatic)
        assertContentEquals(alice.publicKey, r.result().remoteStatic)
        // A different handshake (e.g. a man in the middle) yields an unrelated code.
        val i2 = HandshakeState(NoisePattern.XX, true, ByteArray(0), alice)
        val r2 = HandshakeState(NoisePattern.XX, false, ByteArray(0), mallory)
        r2.readMessage(i2.writeMessage(ByteArray(0)))
        i2.readMessage(r2.writeMessage(ByteArray(0)))
        assertNotEquals(i.result().handshakeHash.toHex(), "")
    }

    @Test
    fun `handshake fails when a message is modified`() {
        val i = HandshakeState(NoisePattern.XX, true, ByteArray(0), alice)
        val r = HandshakeState(NoisePattern.XX, false, ByteArray(0), bob)
        r.readMessage(i.writeMessage(ByteArray(0)))
        val m2 = r.writeMessage("hi".encodeToByteArray())
        m2[40] = (m2[40].toInt() xor 0x55).toByte()
        assertFailsWith<CryptoException> { i.readMessage(m2) }
    }

    @Test
    fun `low order public keys are rejected`() {
        assertFailsWith<CryptoException> { Primitives.dh(alice.privateKeyCopy(), ByteArray(32)) }
    }

    @Test
    fun `hkdf matches RFC 5869 test case 1`() {
        val okm = Primitives.hkdf(
            ikm = "0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b".hexToBytes(),
            salt = "000102030405060708090a0b0c".hexToBytes(),
            info = "f0f1f2f3f4f5f6f7f8f9".hexToBytes(),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }
}
