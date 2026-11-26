package ai.paai.link.core

import ai.paai.link.core.dj.DjRole
import ai.paai.link.core.dj.DjService
import ai.paai.link.core.dj.DjTrackInfo
import ai.paai.link.core.dj.MusicSource
import ai.paai.link.core.group.GroupStatus
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.sleepMillis
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A "song" of [frames] 20 ms frames, every sample [value]. */
private class ToneTrack(private val value: Short, private var frames: Int) : MusicSource {
    var closed = false
    override fun read(pcm: ShortArray): Int {
        if (frames-- <= 0) return 0
        pcm.fill(value)
        return pcm.size
    }
    override fun close() { closed = true }
}

class DjTest {
    private val devices = ArrayList<TestDevice>()

    @AfterTest
    fun tearDown() = devices.forEach { runCatching { it.stop() } }

    @Test
    fun hostPlaysMusicToTheGroupWithPauseRequestsAndStop() {
        val a = TestDevice("Host", 1000).also { devices += it }
        val b = TestDevice("Ben", 2000).also { devices += it }
        val c = TestDevice("Chen", 3000).also { devices += it }
        trustEachOther(a, b); trustEachOther(a, c)
        a.start(); b.start(); c.start()
        b.connectTo(a); c.connectTo(a)
        eventually { a.node.isReachable(b.id) && a.node.isReachable(c.id) }
        assertTrue(runBlocking { a.groups.create("Ride", GroupMode.PTT, listOf(b.id, c.id)) }.isSuccess)
        eventually(what = "invites") { b.groups.invites.value.isNotEmpty() && c.groups.invites.value.isNotEmpty() }
        runBlocking {
            b.groups.acceptInvite(b.groups.invites.value.single().groupId).getOrThrow()
            c.groups.acceptInvite(c.groups.invites.value.single().groupId).getOrThrow()
        }
        eventually(what = "joined") { listOf(a, b, c).all { it.groups.group.value?.let { g -> g.status == GroupStatus.ACTIVE && g.members.size == 3 } == true } }

        val da = DjService(a.node, a.groups, a.media, a.engine, a.scope, bufferMs = 120)
        val db = DjService(b.node, b.groups, b.media, b.engine, b.scope, bufferMs = 120)
        val dc = DjService(c.node, c.groups, c.media, c.engine, c.scope, bufferMs = 120)

        // Members cannot be the DJ in this version.
        assertTrue(runBlocking { db.start() }.isFailure)
        runBlocking { da.start().getOrThrow() }
        eventually(what = "listening") { db.state.value.role == DjRole.LISTENER && dc.state.value.role == DjRole.LISTENER }
        assertEquals("Host", db.state.value.djName)
        assertEquals(2, da.state.value.listeners)

        val song = ToneTrack(7777, frames = 600) // 12 s
        runBlocking { da.play(DjTrackInfo(DeviceId.random(), "Night Ride", "SRCS Band", 12_000), song) }
        eventually(what = "both hear the song") { b.engine.heardCount(7777) > 25 && c.engine.heardCount(7777) > 25 }
        eventually(what = "track info") { db.state.value.track?.title == "Night Ride" && dc.state.value.playing }

        runBlocking { da.pause() }
        eventually(what = "paused") { !db.state.value.playing }
        sleepMillis(600) // buffered audio drains
        val heard = b.engine.heardCount(7777)
        sleepMillis(600)
        assertTrue(b.engine.heardCount(7777) - heard <= 2, "silent while paused")
        runBlocking { da.resume() }
        eventually(what = "resumed") { b.engine.heardCount(7777) > heard + 15 }

        runBlocking { dc.request("Play something by A. R. Rahman") }
        eventually(what = "request at DJ") { da.state.value.requests.singleOrNull()?.let { it.name == "Chen" && it.text.contains("Rahman") } == true }

        runBlocking { da.stop() }
        eventually(what = "stopped everywhere") { db.state.value.role == DjRole.NONE && dc.state.value.role == DjRole.NONE && da.state.value.role == DjRole.NONE }
        assertTrue(song.closed, "the DJ closes the decoder")
        // The walkie-talkie group keeps its own voice audio open; leave it, then everything must be released.
        runBlocking { b.groups.leave(); c.groups.leave(); a.groups.leave() }
        eventually(what = "audio released") { b.engine.allReleased() && c.engine.allReleased() && a.engine.allReleased() }
    }
}
