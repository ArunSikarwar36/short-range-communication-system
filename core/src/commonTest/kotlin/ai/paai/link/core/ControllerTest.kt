package ai.paai.link.core

import ai.paai.link.core.app.SrcsController
import ai.paai.link.core.media.MediaHooks
import ai.paai.link.core.node.NodeConfig
import ai.paai.link.core.util.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The headless app controller used by the iPhone app, exercised end to end over loopback. */
class ControllerTest {
    private val made = ArrayList<SrcsController>()

    private fun config() = NodeConfig(
        tcpPort = 0, udpPort = 0, bindHost = "127.0.0.1",
        advertIntervalMs = 200, pingIntervalMs = 300, linkTimeoutMs = 3_000, routeTimeoutMs = 1_500, reconnectWindowMs = 15_000,
    )

    private fun controller(
        name: String,
        engine: FakeAudioEngine,
        dir: String = Files.createTempDir("srcs-test-"),
        key: ByteArray = SrcsController.newPrivateKey(),
    ) = SrcsController(dir, key, name, engine, object : MediaHooks {}, config(), FAST_TIMERS, 1_200).also { made += it }

    @AfterTest
    fun tearDown() = runBlocking { made.forEach { it.stop() } }

    private fun pair(a: SrcsController, b: SrcsController) {
        b.connect("127.0.0.1", a.tcpPort)
        eventually(what = "pairing requests") { a.snapshot.value.pairings.size == 1 && b.snapshot.value.pairings.size == 1 }
        assertEquals(a.snapshot.value.pairings[0].code, b.snapshot.value.pairings[0].code, "both phones show the same code")
        a.confirmPairing(a.snapshot.value.pairings[0].linkId, true)
        b.confirmPairing(b.snapshot.value.pairings[0].linkId, true)
        eventually(what = "paired and reachable") {
            a.snapshot.value.peers.singleOrNull()?.reachable == true && b.snapshot.value.peers.singleOrNull()?.reachable == true
        }
    }

    @Test
    fun pairingMessagesCallsAndRestart() {
        val engineA = FakeAudioEngine(1000)
        val engineB = FakeAudioEngine(2000)
        val dirB = Files.createTempDir("srcs-iphone-")
        val keyB = SrcsController.newPrivateKey()
        val a = controller("Android host", engineA)
        val b = controller("iPhone", engineB, dirB, keyB)
        runBlocking { assertTrue(a.start()); assertTrue(b.start()) }
        pair(a, b)
        val aId = a.deviceId
        val bId = b.deviceId
        assertEquals("Android host", b.snapshot.value.peers.single().name)

        // Messages with delivery and read receipts.
        b.sendMessage(aId, "hello from the iPhone")
        eventually(what = "delivered") { a.messages(bId).any { it.body == "hello from the iPhone" } && b.messages(aId).singleOrNull()?.status == "Delivered" }
        eventually(what = "unread count") { a.snapshot.value.peers.single().unread == 1 }
        a.markRead(bId)
        eventually(what = "read receipt") { b.messages(aId).single().status == "Read" }

        // A call from the iPhone, answered on the other side, with audio both ways.
        b.call(aId)
        eventually(what = "ringing") { a.snapshot.value.call?.incomingRinging == true }
        assertEquals("iPhone", a.snapshot.value.call!!.peerName)
        a.answer()
        eventually(what = "active") { a.snapshot.value.call?.phase == "ACTIVE" && b.snapshot.value.call?.phase == "ACTIVE" }
        eventually(what = "two-way audio") { engineA.heardCount(2000) > 5 && engineB.heardCount(1000) > 5 }
        b.hangUp()
        eventually(what = "call ended") { a.snapshot.value.call?.live != true && b.snapshot.value.call?.live != true }
        eventually(what = "audio released") { engineA.allReleased() && engineB.allReleased() }

        // Offline: the message is queued; a restarted app (same key and files) still knows its pairing and sends it.
        runBlocking { b.stop() }
        b.sendMessage(aId, "queued while offline")
        eventually(what = "queued") { b.messages(aId).any { it.body == "queued while offline" && it.status == "Waiting for device" } }
        val b2 = controller("iPhone", FakeAudioEngine(2000), dirB, keyB)
        eventually(what = "pairing restored from disk") { b2.snapshot.value.peers.map { it.id } == listOf(aId) }
        assertEquals(2, b2.messages(aId).size, "both sent messages restored from disk")
        runBlocking { assertTrue(b2.start()) }
        b2.connect("127.0.0.1", a.tcpPort)
        eventually(what = "queued message delivered after restart") {
            a.messages(bId).any { it.body == "queued while offline" } && b2.messages(aId).last().status == "Delivered"
        }
        assertTrue(a.snapshot.value.pairings.isEmpty(), "no second pairing prompt for a known device")
    }

    @Test
    fun iphoneJoinsWalkieTalkieGroup() {
        val a = controller("Android host", FakeAudioEngine(1000))
        val b = controller("iPhone", FakeAudioEngine(2000))
        runBlocking { assertTrue(a.start()); assertTrue(b.start()) }
        pair(a, b)
        a.createGroup("Field team", walkieTalkie = true, peerIds = listOf(b.deviceId))
        eventually(what = "invite") { b.snapshot.value.invites.singleOrNull()?.name == "Field team" }
        assertTrue(b.snapshot.value.invites.single().isPtt)
        b.acceptInvite(b.snapshot.value.invites.single().groupId)
        eventually(what = "joined") { b.snapshot.value.group?.status == "ACTIVE" && a.snapshot.value.group?.members?.size == 2 }
        b.pressTalk()
        eventually(what = "talking") { b.snapshot.value.group?.iAmTalking == true && a.snapshot.value.group?.talker == "iPhone" }
        b.releaseTalk()
        eventually(what = "channel free") { a.snapshot.value.group?.talker == "" && b.snapshot.value.group?.iAmTalking == false }
        b.leaveGroup()
        eventually(what = "left") { b.snapshot.value.group == null && a.snapshot.value.group?.members?.size == 1 }
    }
}
