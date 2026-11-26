package ai.paai.link.core

import ai.paai.link.core.group.GroupStatus
import ai.paai.link.core.location.Geo
import ai.paai.link.core.location.GeoFix
import ai.paai.link.core.location.LocationConfig
import ai.paai.link.core.location.LocationService
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.util.currentTimeMillis
import ai.paai.link.core.util.sleepMillis
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoTest {
    @Test
    fun distancesBearingsAndFormatting() {
        // London (Westminster) to New York (Statue of Liberty): about 5 575 km.
        val d = Geo.distanceM(51.5007, -0.1246, 40.6892, -74.0445)
        assertTrue(abs(d - 5_574_800) < 5_000, "London–New York $d")
        assertEquals(0.0, Geo.distanceM(28.6139, 77.2090, 28.6139, 77.2090))
        assertEquals(90.0, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(0.0, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(180.0, Geo.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-9)
        assertEquals("N", Geo.compass(350.0)); assertEquals("NE", Geo.compass(44.0)); assertEquals("W", Geo.compass(270.0))
        assertEquals("35 m", Geo.formatDistance(34.0)); assertEquals("1.2 km", Geo.formatDistance(1_234.0)); assertEquals("14 km", Geo.formatDistance(14_400.0))
        assertEquals(286_139_000, Geo.toE7(28.6139)); assertEquals(-74.0445, Geo.fromE7(Geo.toE7(-74.0445)), 1e-7)
    }
}

class LocationTest {
    private val devices = ArrayList<TestDevice>()
    private val fast = LocationConfig(minIntervalMs = 300, minMoveM = 8.0, heartbeatMs = 60_000)

    private fun device(name: String, tone: Int) = TestDevice(name, tone.toShort()).also { devices += it }
    private fun location(d: TestDevice) = LocationService(d.node, d.groups, d.scope, fast)

    @AfterTest
    fun tearDown() = devices.forEach { runCatching { it.stop() } }

    private fun fix(lat: Double, lon: Double) = GeoFix(lat, lon, 6f, currentTimeMillis())

    @Test
    fun sharingWithPairedPhonesRateLimitAndStop() {
        val a = device("Asha", 1000); val b = device("Ben", 2000)
        trustEachOther(a, b)
        a.start(); b.start()
        b.connectTo(a)
        eventually { a.node.isReachable(b.id) && b.node.isReachable(a.id) }
        val la = location(a); val lb = location(b)

        la.onFix(fix(28.6139, 77.2090))
        sleepMillis(100)
        assertTrue(lb.friends.value.isEmpty(), "nothing is sent before the user opts in")
        la.startSharing(toPaired = true, toGroup = false, durationMs = 60_000)
        eventually(what = "B sees A") { lb.friends.value.singleOrNull()?.let { it.name == "Asha" && abs(it.lat - 28.6139) < 1e-6 } == true }
        val first = lb.friends.value.single().receivedAt

        la.onFix(fix(28.61391, 77.2090)) // ~1 m: not worth sending
        sleepMillis(600)
        assertEquals(first, lb.friends.value.single().receivedAt, "tiny moves are not sent")
        la.onFix(fix(28.6150, 77.2090)) // ~120 m north
        eventually(what = "moved") { abs(lb.friends.value.single().lat - 28.6150) < 1e-6 }
        val f = lb.friends.value.single()
        val bearing = Geo.bearingDeg(28.6139, 77.2090, f.lat, f.lon)
        assertEquals("N", Geo.compass(bearing))
        assertEquals(1, la.sharing.value.recipients)

        la.stopSharing()
        eventually(what = "removed after stop") { lb.friends.value.isEmpty() }
        assertTrue(!la.sharing.value.active)
    }

    @Test
    fun groupLocationGoesThroughTheHostAndSosArrivesOnce() {
        val a = device("Host", 1000); val b = device("Ben", 2000); val c = device("Chen", 3000)
        trustEachOther(a, b); trustEachOther(a, c) // B and C are not paired with each other
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
        val la = location(a); val lb = location(b); val lc = location(c)

        lb.onFix(fix(19.0760, 72.8777))
        lb.startSharing(toPaired = false, toGroup = true, durationMs = 60_000)
        eventually(what = "C sees B through the host") { lc.friends.value.singleOrNull()?.let { it.name == "Ben" && it.viaGroup } == true }
        eventually(what = "host sees B") { la.friends.value.any { it.name == "Ben" } }

        val sent = runBlocking { lc.sendSos("Flat tyre, need help") }
        assertTrue(sent >= 1)
        eventually(what = "SOS at host and B") {
            la.activeSos.value.singleOrNull()?.note == "Flat tyre, need help" && lb.activeSos.value.singleOrNull()?.name == "Chen"
        }
        assertNull(lb.activeSos.value.single().fix, "Chen had no GPS fix")
        sleepMillis(500)
        assertEquals(1, lb.activeSos.value.size, "delivered once")
        lb.dismissSos(lb.activeSos.value.single().alertId)
        assertTrue(lb.activeSos.value.isEmpty())

        lb.stopSharing()
        eventually(what = "C removes B") { lc.friends.value.isEmpty() }
    }
}
