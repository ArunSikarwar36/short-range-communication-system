package ai.paai.link.core

import ai.paai.link.core.group.GroupEvent
import ai.paai.link.core.util.sleepMillis
import ai.paai.link.core.group.GroupStatus
import ai.paai.link.core.protocol.GroupMode
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Walkie-talkie behaviour between two paired phones that should "just work" without extra taps. */
class WalkieTalkieTest {
    private val devices = ArrayList<TestDevice>()

    private fun pair(autoJoin: Boolean = true): Pair<TestDevice, TestDevice> {
        val a = TestDevice("A", 1000, autoJoin = autoJoin).also { devices += it }
        val b = TestDevice("B", 2000, autoJoin = autoJoin).also { devices += it }
        trustEachOther(a, b)
        a.start(); b.start()
        b.connectTo(a)
        eventually { a.node.isReachable(b.id) && b.node.isReachable(a.id) }
        return a to b
    }

    @AfterTest
    fun tearDown() {
        devices.forEach { runCatching { it.stop() } }
    }

    @Test
    fun paired_phone_joins_a_walkie_talkie_automatically_and_hears_the_talker() {
        val (a, b) = pair()
        val events = SyncList<GroupEvent>()
        b.scope.launch { b.groups.events.collect { events += it } }
        assertTrue(runBlocking { a.groups.create("Walkie-talkie", GroupMode.PTT, listOf(b.id)) }.isSuccess)
        eventually(what = "B joined without tapping") {
            b.groups.group.value?.status == GroupStatus.ACTIVE && a.groups.group.value?.members?.size == 2
        }
        assertTrue(b.groups.invites.value.isEmpty(), "no invitation left to accept")
        eventually(what = "auto-join event") { events.snapshot().any { it is GroupEvent.AutoJoined } }

        runBlocking { a.groups.pressTalk() }
        eventually(what = "B hears A") { b.engine.heardCount(1000) > 10 }
        runBlocking { a.groups.releaseTalk() }
        eventually { b.groups.ptt.value.holder == null }
        runBlocking { b.groups.pressTalk() }
        eventually(what = "A hears B") { a.engine.heardCount(2000) > 10 }
    }

    @Test
    fun both_phones_starting_at_once_end_up_on_one_channel() {
        val (a, b) = pair()
        runBlocking {
            val x = async { a.groups.create("Walkie-talkie", GroupMode.PTT, listOf(b.id)) }
            val y = async { b.groups.create("Walkie-talkie", GroupMode.PTT, listOf(a.id)) }
            x.await(); y.await()
        }
        eventually(what = "one shared channel") {
            val ga = a.groups.group.value
            val gb = b.groups.group.value
            ga != null && gb != null && ga.groupId == gb.groupId && ga.status == GroupStatus.ACTIVE && gb.status == GroupStatus.ACTIVE &&
                ga.members.size == 2 && gb.members.size == 2
        }
        // It stays that way (nobody is kicked out by a late message).
        sleepMillis(1_500)
        assertEquals(a.groups.group.value?.groupId, b.groups.group.value?.groupId)
        assertEquals(2, a.groups.group.value?.members?.size)
    }

    @Test
    fun group_calls_and_phones_without_auto_join_still_ask() {
        val (a, b) = pair()
        assertTrue(runBlocking { a.groups.create("Call", GroupMode.CALL, listOf(b.id)) }.isSuccess)
        eventually(what = "invitation shown") { b.groups.invites.value.isNotEmpty() }
        assertEquals(null, b.groups.group.value, "an open-mic group call is never joined without asking")
    }
}
