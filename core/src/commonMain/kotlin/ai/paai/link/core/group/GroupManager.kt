package ai.paai.link.core.group

import ai.paai.link.core.util.currentTimeMillis
import kotlin.concurrent.Volatile
import ai.paai.link.core.crypto.MediaCipher
import ai.paai.link.core.media.MediaCoordinator
import ai.paai.link.core.media.MediaSessionConfig
import ai.paai.link.core.media.MediaTopology
import ai.paai.link.core.media.VoiceSession
import ai.paai.link.core.node.Incoming
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.protocol.GroupMember
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.ptt.FloorArbiter
import ai.paai.link.core.ptt.PttState
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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

enum class GroupRole { HOST, MEMBER }
enum class GroupStatus { JOINING, ACTIVE, RECOVERING }

data class GroupState(
    val groupId: DeviceId,
    val name: String,
    val mode: GroupMode,
    val codec: CodecConfig,
    val epoch: Long,
    val role: GroupRole,
    val status: GroupStatus,
    /** Host first, then succession order. */
    val members: List<GroupMember>,
    /** Host only: invitations not yet answered. */
    val invited: Set<DeviceId> = emptySet(),
) {
    val host: DeviceId? get() = members.firstOrNull()?.id
}

data class GroupInviteInfo(val groupId: DeviceId, val name: String, val mode: GroupMode, val codec: CodecConfig, val from: DeviceId, val fromName: String, val at: Long)

sealed class GroupEvent {
    data class Ended(val name: String, val reason: String) : GroupEvent()
    data class Info(val message: String) : GroupEvent()
}

/**
 * Group voice (full-duplex conference or push-to-talk channel) with a star topology: the host relays
 * audio between members and arbitrates the PTT floor.
 *
 * Group key management: the host creates a random media key per epoch and sends it to each member
 * inside end-to-end sealed GROUP_UPDATE messages. Every membership change starts a new epoch with a
 * new key, so a member who leaves or is removed cannot decrypt later audio, and a new member cannot
 * decrypt earlier audio. Members only accept updates from the current host or, during recovery, from
 * the designated successor.
 *
 * Host recovery: if the host becomes unreachable, the next member in the succession order takes
 * over, issues a new epoch and re-invites the members it is paired with. Others leave after a timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupManager(
    private val node: LinkNode,
    private val media: MediaCoordinator,
    private val scope: CoroutineScope,
    private val isCallActive: () -> Boolean,
    private val maxMembers: () -> Int = { 6 },
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val actor = Dispatchers.Default.limitedParallelism(1)
    private val _group = MutableStateFlow<GroupState?>(null)
    val group: StateFlow<GroupState?> = _group.asStateFlow()
    private val _invites = MutableStateFlow<List<GroupInviteInfo>>(emptyList())
    val invites: StateFlow<List<GroupInviteInfo>> = _invites.asStateFlow()
    private val _ptt = MutableStateFlow(PttState())
    val ptt: StateFlow<PttState> = _ptt.asStateFlow()
    private val _session = MutableStateFlow<VoiceSession?>(null)
    val session: StateFlow<VoiceSession?> = _session.asStateFlow()
    private val _events = MutableSharedFlow<GroupEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<GroupEvent> = _events.asSharedFlow()

    private var mediaKey: ByteArray? = null
    private var nextStreamId = 1
    private var arbiter = FloorArbiter()
    private var pttKeepalive: Job? = null
    private var recoveryJob: Job? = null
    /** Update from the successor that arrived before this device noticed the host was gone. */
    private var successorUpdate: Pair<DeviceId, AppMessage.GroupUpdate>? = null
    private var joinTimeout: Job? = null
    private val unreachableSince = HashMap<DeviceId, Long>()
    @Volatile private var memberIds: List<DeviceId> = emptyList()

    init {
        scope.launch { node.incoming.collect { withContext(actor) { onMessage(it) } } }
        scope.launch { node.events.collect { withContext(actor) { onNodeEvent(it) } } }
        scope.launch(actor) {
            while (isActive) {
                delay(500)
                housekeeping()
            }
        }
    }

    // ================================================================== host API

    /** Create a group and invite paired devices. The creator becomes host and audio starts immediately. */
    suspend fun create(name: String, mode: GroupMode, invitees: List<DeviceId>, codec: CodecConfig = CodecConfig()): Result<Unit> = withContext(actor) {
        if (_group.value != null) return@withContext Result.failure(IllegalStateException("Already in a group"))
        if (media.busy || isCallActive()) return@withContext Result.failure(IllegalStateException("End the current call first"))
        val trusted = invitees.filter { node.isTrusted(it) }.distinct()
        if (trusted.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Choose at least one paired device"))
        val groupId = DeviceId.random()
        nextStreamId = 1
        arbiter = FloorArbiter()
        val key = MediaCipher.newKey()
        mediaKey = key
        val me = GroupMember(node.selfId, node.displayName.take(40), nextStreamId++)
        val st = GroupState(groupId, name.take(40).ifBlank { "Group" }, mode, codec, 1, GroupRole.HOST, GroupStatus.ACTIVE, listOf(me), trusted.toSet())
        setGroup(st)
        if (!startSession(st, key, me.streamId)) {
            setGroup(null)
            return@withContext Result.failure(IllegalStateException("Could not start audio"))
        }
        for (id in trusted) node.send(id, AppMessage.GroupInvite(groupId, st.name, mode, codec))
        node.log.i(TAG, "group '${st.name}' created (${mode.name}), invited ${trusted.size}")
        Result.success(Unit)
    }

    suspend fun invite(peer: DeviceId) = withContext(actor) {
        val g = _group.value ?: return@withContext
        if (g.role != GroupRole.HOST || !node.isTrusted(peer) || g.members.any { it.id == peer }) return@withContext
        setGroup(g.copy(invited = g.invited + peer))
        node.send(peer, AppMessage.GroupInvite(g.groupId, g.name, g.mode, g.codec))
    }

    suspend fun kick(peer: DeviceId) = withContext(actor) {
        val g = _group.value ?: return@withContext
        if (g.role != GroupRole.HOST || peer == node.selfId) return@withContext
        node.send(peer, AppMessage.GroupKick(g.groupId))
        removeMember(peer, "removed")
    }

    // ================================================================== member API

    suspend fun acceptInvite(groupId: DeviceId): Result<Unit> = withContext(actor) {
        val inv = _invites.value.firstOrNull { it.groupId == groupId } ?: return@withContext Result.failure(IllegalStateException("Invitation expired"))
        if (_group.value != null) return@withContext Result.failure(IllegalStateException("Leave the current group first"))
        if (media.busy || isCallActive()) return@withContext Result.failure(IllegalStateException("End the current call first"))
        _invites.value = _invites.value.filter { it.groupId != groupId }
        if (!node.send(inv.from, AppMessage.GroupJoin(groupId))) return@withContext Result.failure(IllegalStateException("Host unreachable"))
        setGroup(GroupState(groupId, inv.name, inv.mode, inv.codec, 0, GroupRole.MEMBER, GroupStatus.JOINING, listOf(GroupMember(inv.from, inv.fromName, 0))))
        joinTimeout?.cancel()
        joinTimeout = scope.launch(actor) {
            delay(10_000)
            if (_group.value?.groupId == groupId && _group.value?.status == GroupStatus.JOINING) {
                endLocally("Host did not respond")
            }
        }
        Result.success(Unit)
    }

    suspend fun declineInvite(groupId: DeviceId) = withContext(actor) {
        val inv = _invites.value.firstOrNull { it.groupId == groupId } ?: return@withContext
        _invites.value = _invites.value.filter { it.groupId != groupId }
        node.send(inv.from, AppMessage.GroupDecline(groupId))
    }

    /** Leave (member) or end the group for everyone (host). */
    suspend fun leave() = withContext(actor) {
        val g = _group.value ?: return@withContext
        if (g.role == GroupRole.HOST) {
            for (m in g.members) if (m.id != node.selfId) node.send(m.id, AppMessage.GroupEnd(g.groupId))
            for (id in g.invited) node.send(id, AppMessage.GroupEnd(g.groupId))
            endLocally("You ended the group")
        } else {
            g.host?.let { node.send(it, AppMessage.GroupLeave(g.groupId)) }
            endLocally("You left the group")
        }
    }

    fun setMuted(muted: Boolean) { _session.value?.muted = muted }
    fun setMemberMuted(id: DeviceId, muted: Boolean) { _session.value?.setSourceMuted(id, muted) }

    // ================================================================== push-to-talk

    suspend fun pressTalk() = withContext(actor) {
        val g = _group.value ?: return@withContext
        if (g.mode != GroupMode.PTT || g.status != GroupStatus.ACTIVE || _ptt.value.talking || _ptt.value.requesting) return@withContext
        _ptt.value = _ptt.value.copy(requesting = true, denied = false, revoked = false)
        if (g.role == GroupRole.HOST) {
            onFloorDecision(arbiter.request(node.selfId, clock()))
            broadcastFloor()
        } else {
            val host = g.host ?: return@withContext
            if (!node.send(host, AppMessage.PttRequest(g.groupId))) _ptt.value = _ptt.value.copy(requesting = false, denied = true)
        }
    }

    /** Release the floor. Also called on touch cancel, screen off and app backgrounding. */
    suspend fun releaseTalk() = withContext(actor) {
        val g = _group.value ?: return@withContext
        val p = _ptt.value
        if (!p.talking && !p.requesting) return@withContext
        stopTalking()
        _ptt.value = _ptt.value.copy(requesting = false)
        if (!p.talking) return@withContext
        if (g.role == GroupRole.HOST) {
            if (arbiter.release(node.selfId, p.floorSeq)) broadcastFloor()
        } else {
            g.host?.let { node.send(it, AppMessage.PttRelease(g.groupId, p.floorSeq)) }
        }
    }

    private fun onFloorDecision(d: FloorArbiter.Decision) {
        when (d) {
            is FloorArbiter.Decision.Granted -> startTalking(d.floorSeq)
            is FloorArbiter.Decision.Denied -> _ptt.value = _ptt.value.copy(requesting = false, denied = true, holder = d.holder)
        }
    }

    private fun startTalking(seq: Long) {
        if (!_ptt.value.requesting) {
            // Button already released before the grant arrived: give the floor straight back.
            val g = _group.value ?: return
            if (g.role == GroupRole.HOST) { if (arbiter.release(node.selfId, seq)) broadcastFloor() }
            else g.host?.let { h -> scope.launch { node.send(h, AppMessage.PttRelease(g.groupId, seq)) } }
            return
        }
        _ptt.value = _ptt.value.copy(requesting = false, talking = true, floorSeq = seq, holder = node.selfId, denied = false)
        _session.value?.transmitting = true
        pttKeepalive?.cancel()
        pttKeepalive = scope.launch(actor) {
            while (isActive) {
                val g = _group.value ?: break
                if (g.role == GroupRole.HOST) arbiter.keepalive(node.selfId, seq, clock())
                else g.host?.let { node.send(it, AppMessage.PttKeepalive(g.groupId, seq)) }
                delay(1_000)
            }
        }
    }

    private fun stopTalking() {
        _session.value?.transmitting = false
        pttKeepalive?.cancel()
        pttKeepalive = null
        _ptt.value = _ptt.value.copy(talking = false)
    }

    private fun broadcastFloor() {
        val g = _group.value ?: return
        val holder = arbiter.holder
        _ptt.value = _ptt.value.copy(holder = holder)
        val msg = AppMessage.PttFloorState(g.groupId, holder ?: DeviceId.ZERO, arbiter.floorSeq)
        for (m in g.members) if (m.id != node.selfId) scope.launch { node.send(m.id, msg) }
    }

    // ================================================================== incoming

    private suspend fun onMessage(inc: Incoming) {
        val m = inc.message
        val g = _group.value
        when (m) {
            is AppMessage.GroupInvite -> {
                if (_invites.value.none { it.groupId == m.groupId } && g?.groupId != m.groupId) {
                    _invites.value = _invites.value + GroupInviteInfo(m.groupId, m.name, m.mode, m.codec.validate(), inc.from, node.peerName(inc.from), clock())
                }
            }
            is AppMessage.GroupJoin -> if (g != null && g.groupId == m.groupId && g.role == GroupRole.HOST) {
                if (inc.from !in g.invited) return
                if (g.members.size >= maxMembers().coerceAtMost(ProtocolLimits.MAX_GROUP_MEMBERS)) {
                    node.send(inc.from, AppMessage.GroupEnd(g.groupId))
                    _events.tryEmit(GroupEvent.Info("${node.peerName(inc.from)} could not join: group is full"))
                    return
                }
                val member = GroupMember(inc.from, node.peerName(inc.from).take(40), nextStreamId++)
                newEpoch(g.copy(members = g.members + member, invited = g.invited - inc.from))
                _events.tryEmit(GroupEvent.Info("${member.name} joined"))
            }
            is AppMessage.GroupDecline -> if (g != null && g.groupId == m.groupId && g.role == GroupRole.HOST) {
                setGroup(g.copy(invited = g.invited - inc.from))
                _events.tryEmit(GroupEvent.Info("${node.peerName(inc.from)} declined"))
            }
            is AppMessage.GroupLeave -> if (g != null && g.groupId == m.groupId && g.role == GroupRole.HOST) {
                removeMember(inc.from, "left")
            }
            is AppMessage.GroupUpdate -> onUpdate(inc.from, m)
            is AppMessage.GroupKick -> if (g != null && g.groupId == m.groupId && inc.from == g.host) endLocally("Removed from the group by the host")
            is AppMessage.GroupEnd -> {
                _invites.value = _invites.value.filter { it.groupId != m.groupId || it.from != inc.from }
                if (g != null && g.groupId == m.groupId && inc.from == g.host) endLocally("The host ended the group")
            }
            // --- floor control, host side
            is AppMessage.PttRequest -> if (isHostOf(g, m.groupId) && isMember(g!!, inc.from)) {
                when (val d = arbiter.request(inc.from, clock())) {
                    is FloorArbiter.Decision.Granted -> {
                        node.send(inc.from, AppMessage.PttGranted(g.groupId, d.floorSeq, arbiter.maxTalkMs))
                        broadcastFloor()
                    }
                    is FloorArbiter.Decision.Denied -> node.send(inc.from, AppMessage.PttDenied(g.groupId, d.holder))
                }
            }
            is AppMessage.PttRelease -> if (isHostOf(g, m.groupId)) {
                if (arbiter.release(inc.from, m.floorSeq)) broadcastFloor()
            }
            is AppMessage.PttKeepalive -> if (isHostOf(g, m.groupId)) arbiter.keepalive(inc.from, m.floorSeq, clock())
            // --- floor control, member side
            is AppMessage.PttGranted -> if (g != null && g.groupId == m.groupId && inc.from == g.host) startTalking(m.floorSeq)
            is AppMessage.PttDenied -> if (g != null && g.groupId == m.groupId && inc.from == g.host) {
                _ptt.value = _ptt.value.copy(requesting = false, denied = true, holder = m.holder)
            }
            is AppMessage.PttRevoked -> if (g != null && g.groupId == m.groupId && inc.from == g.host && _ptt.value.floorSeq == m.floorSeq) {
                stopTalking()
                _ptt.value = _ptt.value.copy(revoked = true)
            }
            is AppMessage.PttFloorState -> if (g != null && g.groupId == m.groupId && inc.from == g.host) {
                val holder = m.holder.takeUnless { it.isZero }
                if (_ptt.value.talking && holder != node.selfId) stopTalking()
                _ptt.value = _ptt.value.copy(holder = holder)
            }
            else -> Unit
        }
    }

    private fun isHostOf(g: GroupState?, groupId: DeviceId) = g != null && g.groupId == groupId && g.role == GroupRole.HOST
    private fun isMember(g: GroupState, id: DeviceId) = g.members.any { it.id == id }

    private suspend fun onUpdate(from: DeviceId, u: AppMessage.GroupUpdate) {
        val g = _group.value ?: return
        if (g.groupId != u.groupId || u.epoch <= g.epoch) return
        val currentHost = g.host
        val successor = g.members.getOrNull(1)?.id
        val authorised = from == u.host && (from == currentHost || (g.status == GroupStatus.RECOVERING && from == successor) ||
            (g.status == GroupStatus.JOINING && from == currentHost))
        if (!authorised) {
            if (g.status == GroupStatus.ACTIVE && from == successor && from == u.host) {
                // The successor detected the host loss first. Keep its update and apply it once this
                // device has confirmed the loss too, rather than dropping it and timing out later.
                successorUpdate = from to u
                node.log.i(TAG, "${node.peerName(from)} is taking over; waiting to confirm the host is gone")
                if (currentHost != null && unreachableSince.containsKey(currentHost)) beginRecovery(g)
                return
            }
            node.log.w(TAG, "ignored group update from ${node.peerName(from)}")
            return
        }
        successorUpdate = null
        val me = u.members.firstOrNull { it.id == node.selfId }
        if (me == null) {
            endLocally("No longer a member of '${u.name}'")
            return
        }
        joinTimeout?.cancel()
        recoveryJob?.cancel()
        val st = GroupState(u.groupId, u.name, u.mode, u.codec, u.epoch, GroupRole.MEMBER, GroupStatus.ACTIVE, u.members)
        val hostChanged = currentHost != u.host
        setGroup(st)
        mediaKey = u.mediaKey
        val s = _session.value
        if (s == null) {
            if (!startSession(st, u.mediaKey, me.streamId)) {
                g.host?.let { node.send(it, AppMessage.GroupLeave(g.groupId)) }
                endLocally("Could not start audio")
            } else if (g.status == GroupStatus.JOINING) {
                _events.tryEmit(GroupEvent.Info("Joined '${u.name}'"))
            }
        } else {
            s.rekey(u.mediaKey)
            if (hostChanged) {
                s.updateTopology(MediaTopology.GroupMember(u.host))
                _events.tryEmit(GroupEvent.Info("${node.peerName(u.host)} is now hosting"))
                if (_ptt.value.talking) stopTalking()
                _ptt.value = PttState()
            }
        }
    }

    private fun onNodeEvent(e: NodeEvent) {
        val g = _group.value ?: return
        when (e) {
            is NodeEvent.PeerUnreachable -> {
                if (e.peer in g.members.map { it.id }) unreachableSince.getOrPut(e.peer) { clock() }
            }
            is NodeEvent.PeerReachable -> unreachableSince.remove(e.peer)
            else -> Unit
        }
    }

    private suspend fun housekeeping() {
        val g = _group.value ?: return
        val now = clock()
        if (g.role == GroupRole.HOST) {
            arbiter.tick(now)?.let { revoked ->
                if (revoked == node.selfId) {
                    stopTalking()
                    _ptt.value = _ptt.value.copy(revoked = true)
                } else {
                    node.send(revoked, AppMessage.PttRevoked(g.groupId, arbiter.floorSeq))
                }
                broadcastFloor()
            }
            for ((id, since) in unreachableSince.toMap()) {
                if (now - since > MEMBER_LOSS_MS && g.members.any { it.id == id }) {
                    unreachableSince.remove(id)
                    removeMember(id, "lost connection")
                }
            }
        } else if (g.status == GroupStatus.ACTIVE) {
            val host = g.host ?: return
            val since = unreachableSince[host] ?: return
            if (now - since > HOST_LOSS_MS) beginRecovery(g)
        }
    }

    // ================================================================== membership internals

    private suspend fun removeMember(id: DeviceId, why: String) {
        val g = _group.value ?: return
        if (g.role != GroupRole.HOST || g.members.none { it.id == id }) return
        if (arbiter.memberLeft(id)) broadcastFloor()
        val name = g.members.first { it.id == id }.name
        newEpoch(g.copy(members = g.members.filter { it.id != id }))
        _events.tryEmit(GroupEvent.Info("$name $why"))
    }

    /** Host: increment the epoch, rotate the media key and send the snapshot to every member. */
    private suspend fun newEpoch(g: GroupState) {
        val key = MediaCipher.newKey()
        mediaKey = key
        val st = g.copy(epoch = g.epoch + 1)
        setGroup(st)
        _session.value?.rekey(key)
        val update = AppMessage.GroupUpdate(st.groupId, st.epoch, st.name, st.mode, st.codec, key, st.members)
        for (m in st.members) if (m.id != node.selfId) node.send(m.id, update)
    }

    private suspend fun beginRecovery(g: GroupState) {
        val successor = g.members.getOrNull(1)?.id
        setGroup(g.copy(status = GroupStatus.RECOVERING))
        if (_ptt.value.talking) stopTalking()
        _ptt.value = PttState()
        if (successor == node.selfId) {
            node.log.i(TAG, "host lost; taking over group '${g.name}'")
            val remaining = g.members.drop(1).filter { it.id == node.selfId || (node.isTrusted(it.id) && node.isReachable(it.id)) }
            nextStreamId = (remaining.maxOfOrNull { it.streamId } ?: 0) + 1
            arbiter = FloorArbiter()
            val st = g.copy(role = GroupRole.HOST, status = GroupStatus.ACTIVE, members = remaining, invited = emptySet())
            setGroup(st)
            _session.value?.updateTopology(MediaTopology.GroupHub { memberIds })
            _events.tryEmit(GroupEvent.Info("Host lost — you are now hosting"))
            newEpoch(st)
        } else {
            successorUpdate?.let { (from, update) ->
                successorUpdate = null
                onUpdate(from, update)
                if (_group.value?.status == GroupStatus.ACTIVE) return
            }
            _events.tryEmit(GroupEvent.Info("Host lost — waiting for ${g.members.getOrNull(1)?.name ?: "a new host"}"))
            recoveryJob?.cancel()
            recoveryJob = scope.launch(actor) {
                delay(RECOVERY_WAIT_MS)
                if (_group.value?.status == GroupStatus.RECOVERING) endLocally("Lost connection to the group")
            }
        }
    }

    private fun startSession(g: GroupState, key: ByteArray, streamId: Int): Boolean {
        val topology = if (g.role == GroupRole.HOST) MediaTopology.GroupHub { memberIds } else MediaTopology.GroupMember(g.host!!)
        val s = media.open(MediaSessionConfig(g.groupId, key, g.codec, streamId, topology, transmitInitially = g.mode == GroupMode.CALL))
            ?: return false
        s.acceptSource = { src ->
            val cur = _group.value
            cur == null || cur.mode != GroupMode.PTT || cur.role != GroupRole.HOST || arbiter.holder == src
        }
        _session.value = s
        return true
    }

    private fun endLocally(reason: String) {
        val g = _group.value
        stopTalking()
        recoveryJob?.cancel()
        joinTimeout?.cancel()
        _session.value?.let { media.close(it) }
        _session.value = null
        setGroup(null)
        _ptt.value = PttState()
        unreachableSince.clear()
        successorUpdate = null
        mediaKey = null
        if (g != null) {
            node.log.i(TAG, "group '${g.name}' closed: $reason")
            _events.tryEmit(GroupEvent.Ended(g.name, reason))
        }
    }

    private fun setGroup(g: GroupState?) {
        _group.value = g
        memberIds = g?.members?.map { it.id } ?: emptyList()
    }

    companion object {
        private const val TAG = "group"
        const val MEMBER_LOSS_MS = 10_000L
        const val HOST_LOSS_MS = 4_000L
        const val RECOVERY_WAIT_MS = 15_000L
    }
}
