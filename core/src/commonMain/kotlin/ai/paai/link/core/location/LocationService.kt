package ai.paai.link.core.location

import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.group.GroupRole
import ai.paai.link.core.node.Incoming
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.util.DedupeSet
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A paired phone's last shared position. */
data class FriendLocation(
    val id: DeviceId,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    val fixAt: Long,
    val receivedAt: Long,
    /** Sender's sharing end time. */
    val until: Long,
    val viaGroup: Boolean,
)

data class SosAlert(
    val alertId: DeviceId,
    val from: DeviceId,
    val name: String,
    /** Null when the sender had no GPS fix. */
    val fix: GeoFix?,
    val note: String,
    val receivedAt: Long,
)

data class SharingState(
    val active: Boolean = false,
    val toPaired: Boolean = false,
    val toGroup: Boolean = false,
    /** Wall-clock end of sharing. */
    val until: Long = 0,
    val lastSentAt: Long = 0,
    val recipients: Int = 0,
)

data class LocationConfig(
    /** Never send more often than this. */
    val minIntervalMs: Long = 5_000,
    /** Send when moved at least this far… */
    val minMoveM: Double = 8.0,
    /** …or when this long has passed without an update. */
    val heartbeatMs: Long = 60_000,
    /** Friends' positions disappear this long after their last update. */
    val staleAfterMs: Long = 30 * 60_000L,
)

/**
 * Opt-in live location and SOS over the SRCS network. Positions go end-to-end sealed to paired
 * phones and, for a group, through the group host (which forwards them like group text, and is
 * trusted to do so). Sharing always has an end time and can be stopped at any moment; stopping
 * tells every recipient to remove the position.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationService(
    private val node: LinkNode,
    private val groups: GroupManager?,
    private val scope: CoroutineScope,
    private val config: LocationConfig = LocationConfig(),
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val actor = Dispatchers.Default.limitedParallelism(1)

    private val _sharing = MutableStateFlow(SharingState())
    val sharing: StateFlow<SharingState> = _sharing.asStateFlow()
    private val _myFix = MutableStateFlow<GeoFix?>(null)
    val myFix: StateFlow<GeoFix?> = _myFix.asStateFlow()
    private val _friends = MutableStateFlow<List<FriendLocation>>(emptyList())
    val friends: StateFlow<List<FriendLocation>> = _friends.asStateFlow()
    private val _sos = MutableSharedFlow<SosAlert>(extraBufferCapacity = 16)
    /** Newly received SOS alerts (for a loud notification). */
    val sosReceived: SharedFlow<SosAlert> = _sos.asSharedFlow()
    private val _activeSos = MutableStateFlow<List<SosAlert>>(emptyList())
    /** SOS alerts not yet dismissed by the user, newest first. */
    val activeSos: StateFlow<List<SosAlert>> = _activeSos.asStateFlow()

    private var lastSentFix: GeoFix? = null
    private var sentTo: Set<DeviceId> = emptySet()
    private var sentGroup: DeviceId? = null
    private val seenSos = DedupeSet(256)

    init {
        scope.launch { node.incoming.collect { withContext(actor) { onIncoming(it) } } }
        scope.launch(actor) {
            while (isActive) {
                delay(2_000)
                tick()
            }
        }
    }

    // ------------------------------------------------------------------ sharing my location

    /** Start sharing with all reachable paired phones and/or the current group, for [durationMs]. */
    fun startSharing(toPaired: Boolean, toGroup: Boolean, durationMs: Long) {
        scope.launch(actor) {
            if (!toPaired && !toGroup) return@launch
            _sharing.value = SharingState(active = true, toPaired = toPaired, toGroup = toGroup, until = clock() + durationMs)
            lastSentFix = null
            _myFix.value?.let { send(it, force = true) }
        }
    }

    fun stopSharing() {
        scope.launch(actor) { stopLocked() }
    }

    /** New GPS fix from the platform. */
    fun onFix(fix: GeoFix) {
        scope.launch(actor) {
            _myFix.value = fix
            if (_sharing.value.active) send(fix, force = false)
        }
    }

    private suspend fun stopLocked() {
        val s = _sharing.value
        if (!s.active) return
        _sharing.value = SharingState()
        val me = node.selfId
        for (peer in sentTo) node.send(peer, AppMessage.LocationStop(me, DeviceId.ZERO))
        sentGroup?.let { gid -> groupTargets(gid).forEach { node.send(it, AppMessage.LocationStop(me, gid)) } }
        sentTo = emptySet()
        sentGroup = null
        lastSentFix = null
    }

    private suspend fun send(fix: GeoFix, force: Boolean) {
        val s = _sharing.value
        val now = clock()
        if (!s.active) return
        if (now >= s.until) { stopLocked(); return }
        val last = lastSentFix
        if (!force && last != null) {
            if (now - s.lastSentAt < config.minIntervalMs) return
            val moved = Geo.distanceM(last.lat, last.lon, fix.lat, fix.lon)
            if (moved < config.minMoveM && now - s.lastSentAt < config.heartbeatMs) return
        }
        val me = node.selfId
        val name = node.displayName
        var count = 0
        // Group copies first: the host must get the group copy so it forwards to the other members.
        val gid = if (s.toGroup) groups?.group?.value?.groupId else null
        val viaGroup = gid?.let { groupTargets(it) }.orEmpty().toSet()
        if (gid != null) {
            for (t in viaGroup) {
                if (node.send(t, AppMessage.Location(me, name, gid, fix.latE7, fix.lonE7, fix.accuracyM.toInt(), fix.at, s.until))) count++
            }
        }
        val peers = if (s.toPaired) node.peers.value.filter { it.trusted && it.reachable && it.id !in viaGroup }.map { it.id }.toSet() else emptySet()
        for (p in peers) {
            if (node.send(p, AppMessage.Location(me, name, DeviceId.ZERO, fix.latE7, fix.lonE7, fix.accuracyM.toInt(), fix.at, s.until))) count++
        }
        sentTo = sentTo + peers
        sentGroup = gid ?: sentGroup
        lastSentFix = fix
        _sharing.value = s.copy(lastSentAt = now, recipients = count)
    }

    /** Group members send to the host; the host sends to every member. */
    private fun groupTargets(groupId: DeviceId): List<DeviceId> {
        val g = groups?.group?.value ?: return emptyList()
        if (g.groupId != groupId) return emptyList()
        return if (g.role == GroupRole.HOST) g.members.map { it.id }.filter { it != node.selfId } else listOfNotNull(g.host)
    }

    private suspend fun tick() {
        val now = clock()
        val s = _sharing.value
        if (s.active && now >= s.until) stopLocked()
        // Heartbeat while standing still, so receivers know the position is current.
        if (s.active && now - s.lastSentAt >= config.heartbeatMs) _myFix.value?.let { send(it, force = true) }
        val fresh = _friends.value.filter { now - it.receivedAt < config.staleAfterMs && now < it.until + 120_000 }
        if (fresh.size != _friends.value.size) _friends.value = fresh
    }

    // ------------------------------------------------------------------ SOS

    /** Sends an SOS (with the last known position, if any) to every paired phone and the group. Returns recipients. */
    suspend fun sendSos(note: String): Int = withContext(actor) {
        val fix = _myFix.value
        val me = node.selfId
        val id = DeviceId.random()
        seenSos.add(id)
        var count = 0
        val g = groups?.group?.value
        val viaGroup = g?.let { groupTargets(it.groupId) }.orEmpty().toSet()
        if (g != null) {
            for (t in viaGroup) {
                if (node.send(t, sosMessage(id, me, g.groupId, fix, note), storeAndForward = true)) count++
            }
        }
        for (t in node.peers.value.filter { it.trusted && it.id !in viaGroup }.map { it.id }) {
            if (node.send(t, sosMessage(id, me, DeviceId.ZERO, fix, note), storeAndForward = true)) count++
        }
        node.log.i(TAG, "SOS sent to $count phone(s)")
        count
    }

    private fun sosMessage(id: DeviceId, me: DeviceId, gid: DeviceId, fix: GeoFix?, note: String) = AppMessage.Sos(
        id, me, node.displayName, gid, fix != null, fix?.latE7 ?: 0, fix?.lonE7 ?: 0, fix?.accuracyM?.toInt() ?: 0, fix?.at ?: 0,
        note.trim().take(140),
    )

    fun dismissSos(alertId: DeviceId) {
        _activeSos.value = _activeSos.value.filter { it.alertId != alertId }
    }

    // ------------------------------------------------------------------ receiving

    private suspend fun onIncoming(inc: Incoming) {
        when (val m = inc.message) {
            is AppMessage.Location -> {
                if (!accept(inc.from, m.subject, m.groupId)) return
                if (m.subject == node.selfId) return
                forwardIfHost(inc.from, m.groupId) { m }
                val f = FriendLocation(
                    m.subject, node.trustedPeer(m.subject)?.name ?: m.subjectName, Geo.fromE7(m.latE7), Geo.fromE7(m.lonE7),
                    m.accuracyM, m.fixAt, clock(), m.until, viaGroup = !m.groupId.isZero,
                )
                _friends.value = (_friends.value.filter { it.id != m.subject } + f).sortedBy { it.name.lowercase() }
            }
            is AppMessage.LocationStop -> {
                if (!accept(inc.from, m.subject, m.groupId)) return
                forwardIfHost(inc.from, m.groupId) { m }
                _friends.value = _friends.value.filter { it.id != m.subject }
            }
            is AppMessage.Sos -> {
                if (!accept(inc.from, m.subject, m.groupId) || m.subject == node.selfId) return
                if (!seenSos.add(m.alertId)) return
                forwardIfHost(inc.from, m.groupId, storeAndForward = true) { m }
                val fix = if (m.hasFix) GeoFix(Geo.fromE7(m.latE7), Geo.fromE7(m.lonE7), m.accuracyM.toFloat(), m.fixAt) else null
                val alert = SosAlert(m.alertId, m.subject, node.trustedPeer(m.subject)?.name ?: m.subjectName, fix, m.note, clock())
                _activeSos.value = (listOf(alert) + _activeSos.value).take(10)
                _sos.tryEmit(alert)
                node.log.w(TAG, "SOS received from ${alert.name}")
            }
            else -> {}
        }
    }

    /**
     * Direct messages must come from the subject itself. Group messages may also come from the
     * group's host (forwarding for a member it reports as the subject).
     */
    private fun accept(from: DeviceId, subject: DeviceId, groupId: DeviceId): Boolean {
        if (from == subject) return true
        if (groupId.isZero) return false
        val g = groups?.group?.value ?: return false
        return g.groupId == groupId && g.host == from && g.members.any { it.id == subject }
    }

    private suspend fun forwardIfHost(from: DeviceId, groupId: DeviceId, storeAndForward: Boolean = false, msg: () -> AppMessage) {
        if (groupId.isZero) return
        val g = groups?.group?.value ?: return
        if (g.groupId != groupId || g.role != GroupRole.HOST || g.members.none { it.id == from }) return
        for (m in g.members) {
            if (m.id == from || m.id == node.selfId) continue
            node.send(m.id, msg(), storeAndForward)
        }
    }

    private companion object { const val TAG = "location" }
}
