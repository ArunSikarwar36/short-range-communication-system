package ai.paai.link

import ai.paai.link.audio.OpusDecoder
import ai.paai.link.audio.OpusEncoder
import ai.paai.link.audio.OpusNative
import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.messaging.StoredMessage
import ai.paai.link.core.node.TrustedPeer
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.util.DeviceId
import ai.paai.link.data.LinkDatabase
import ai.paai.link.data.RoomMessageStore
import ai.paai.link.data.RoomTrustStore
import ai.paai.link.security.KeystoreIdentity
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class OpusCodecTest {
    private val codec = CodecConfig()

    @Test
    fun nativeLibraryLoads() {
        assertTrue(OpusNative.version().startsWith("libopus"))
    }

    @Test
    fun encodeDecodeRoundTripPreservesSignalEnergy() {
        val enc = OpusEncoder(codec)
        val dec = OpusDecoder(codec)
        val n = codec.frameSamples
        val pcm = ShortArray(n)
        val out = ShortArray(n)
        val packet = ByteArray(1200)
        var inEnergy = 0.0
        var outEnergy = 0.0
        for (frame in 0 until 50) {
            for (i in 0 until n) pcm[i] = (8000 * sin(2 * PI * 440 * (frame * n + i) / codec.sampleRate)).toInt().toShort()
            val len = enc.encode(pcm, packet)
            assertTrue("encoded length $len", len in 1..400)
            assertEquals(n, dec.decode(packet.copyOf(len), out))
            if (frame >= 10) { // skip codec warm-up
                inEnergy += pcm.sumOf { abs(it.toDouble()) }
                outEnergy += out.sumOf { abs(it.toDouble()) }
            }
        }
        assertTrue("decoded energy ratio ${outEnergy / inEnergy}", outEnergy / inEnergy in 0.5..1.5)
        // Packet loss concealment produces a full frame.
        assertEquals(n, dec.decode(null, out))
        enc.setBitrate(12_000)
        assertTrue(enc.encode(pcm, packet) > 0)
        enc.close(); dec.close()
        assertEquals(-1, enc.encode(pcm, packet)) // closed codecs refuse work instead of crashing
    }
}

@RunWith(AndroidJUnit4::class)
class RoomStorageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = Room.inMemoryDatabaseBuilder(context, LinkDatabase::class.java).build()

    @After
    fun close() = db.close()

    @Test
    fun duplicateMessagesAreRejectedAndPendingQueryWorks() = runBlocking {
        val store = RoomMessageStore(db.messages())
        val m = StoredMessage(DeviceId.random(), DeviceId.random(), false, DeviceId.random(), "A", DeviceId.random(), true, "hi", 1, status = MessageStatus.QUEUED)
        assertTrue(store.insert(m))
        assertFalse(store.insert(m.copy(body = "changed")))
        assertEquals("hi", store.get(m.messageId)!!.body)
        assertEquals(1, store.pendingOutgoing().size)
        store.update(m.copy(status = MessageStatus.DELIVERED))
        assertEquals(0, store.pendingOutgoing().size)
        assertEquals(1, store.deleteOlderThan(10))
    }

    @Test
    fun trustRecordsPersistAndKeepLocalNames() = runBlocking {
        val trust = RoomTrustStore(db.peers())
        val key = ByteArray(32) { it.toByte() }
        val id = DeviceId.fromPublicKey(key)
        trust.save(TrustedPeer(id, key, "Phone", 5))
        db.peers().rename(id.hex, "Dad")
        trust.save(TrustedPeer(id, key, "Phone 2", 6))
        val loaded = trust.loadAll().single()
        assertEquals("Dad", loaded.name)
        assertArrayEquals(key, loaded.staticKey)
        trust.remove(id)
        assertTrue(trust.loadAll().isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
class KeystoreIdentityTest {
    @Test
    fun identityIsStableAcrossLoads() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val (a, _) = KeystoreIdentity.loadOrCreate(context)
        val (b, created) = KeystoreIdentity.loadOrCreate(context)
        assertFalse(created)
        assertArrayEquals(a.publicKey, b.publicKey)
    }
}
