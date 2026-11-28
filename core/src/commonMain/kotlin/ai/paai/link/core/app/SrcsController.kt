package ai.paai.link.core.app

import ai.paai.link.core.call.CallManager
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.call.CallTimer
import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.dj.DjRole
import ai.paai.link.core.dj.DjService
import ai.paai.link.core.dj.DjTrackInfo
import ai.paai.link.core.dj.MusicSource
import ai.paai.link.core.group.GroupEvent
import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.group.GroupRole
import ai.paai.link.core.location.Geo
import ai.paai.link.core.location.GeoFix
import ai.paai.link.core.location.LocationService
import ai.paai.link.core.maps.MapAreaManager
import ai.paai.link.core.maps.MapDownloadView
import ai.paai.link.core.maps.MapRegion
import ai.paai.link.core.media.AudioEngine
import ai.paai.link.core.media.MediaCoordinator
import ai.paai.link.core.media.MediaHooks
import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.messaging.MessagingService
import ai.paai.link.core.messaging.StoredMessage
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.node.LogBuffer
import ai.paai.link.core.node.NodeConfig
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.transport.WifiJoin
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.Files
import ai.paai.link.core.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ai.paai.link.core.protocol.AppMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

// ---------------------------------------------------------------------------------------------
// Snapshot types: plain values the UI (SwiftUI on iOS) renders. IDs are hex strings.

data class PairingView(val linkId: Long, val name: String, val code: String, val waitingForOther: Boolean)

data class PeerView(
    val id: String,
    val name: String,
    val reachable: Boolean,
    val direct: Boolean,
    /** Hop count when reached through a relay, 0 otherwise. */
    val hops: Int,
    val unread: Int,
    val lastMessage: String,
)

data class CallView(
    /** CallPhase name: OUTGOING_INVITE, REMOTE_RINGING, INCOMING_RINGING, CONNECTING, ACTIVE, RECONNECTING, ENDING, ENDED, FAILED. */
    val phase: String,
    val peerId: String,
    val peerName: String,
    val outgoing: Boolean,
    /** Wall-clock ms when audio started, 0 if not yet. */
    val activeSince: Long,
    val muted: Boolean,
    /** Human-readable status line, e.g. "Ringing…" or "Call declined". */
    val status: String,
    val incomingRinging: Boolean,
    val live: Boolean,
)

data class MemberView(val id: String, val name: String, val isHost: Boolean, val isMe: Boolean, val talking: Boolean)

data class GroupView(
    val id: String,
    val name: String,
    val isPtt: Boolean,
    val isHost: Boolean,
    /** JOINING, ACTIVE or RECOVERING. */
    val status: String,
    val members: List<MemberView>,
    /** Name of the current talker in walkie-talkie mode, empty when the channel is free. */
    val talker: String,
    val iAmTalking: Boolean,
    val requesting: Boolean,
    val denied: Boolean,
)

data class InviteView(val groupId: String, val name: String, val fromName: String, val isPtt: Boolean)

data class MessageView(
    val id: String,
    val outgoing: Boolean,
    val author: String,
    val body: String,
    val sentAt: Long,
    /** Empty for incoming messages; "Sending", "Delivered", "Read", "Failed"… for outgoing ones. */
    val status: String,
)

data class FriendView(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
    /** "240 m NE", or empty while this phone has no GPS fix. */
    val distanceText: String,
    /** Seconds since the friend's position was measured. */
    val ageSeconds: Long,
    val viaGroup: Boolean,
)

data class SosView(
    val alertId: String,
    val name: String,
    val note: String,
    val hasFix: Boolean,
    val lat: Double,
    val lon: Double,
    val distanceText: String,
    val receivedAt: Long,
)

data class LocationView(
    val sharing: Boolean,
    val toPaired: Boolean,
    val toGroup: Boolean,
    /** Wall-clock end of sharing, 0 when not sharing. */
    val sharingUntil: Long,
    val recipients: Int,
    val hasFix: Boolean,
    val lat: Double,
    val lon: Double,
    val accuracyM: Int,
)

data class DjView(
    /** NONE, DJ or LISTENER. */
    val role: String,
    val djName: String,
    val title: String,
    val artist: String,
    val playing: Boolean,
    val ended: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val listeners: Int,
    /** "Name: request" lines, newest last. */
    val requests: List<String>,
)

data class AppSnapshot(
    val online: Boolean,
    val busy: Boolean,
    val displayName: String,
    val deviceId: String,
    val fingerprint: String,
    val linkCount: Int,
    val pairings: List<PairingView>,
    val peers: List<PeerView>,
    val call: CallView?,
    val group: GroupView?,
    val invites: List<InviteView>,
    /** Changes whenever any stored message changes; re-read [SrcsController.messages] when it does. */
    val messagesVersion: Long,
    val recentLog: List<String>,
    /** Phones to draw on the Nearby radar (paired and linked, relayed, or waiting for pairing). */
    val radar: List<RadarDot>,
    val location: LocationView,
    val friends: List<FriendView>,
    val sos: List<SosView>,
    /** Offline map areas on this phone, and the current map download. */
    val mapAreas: List<MapRegion>,
    val mapDownload: MapDownloadView,
    val dj: DjView,
)

/** Returned by the watch functions; call [cancel] to stop receiving updates. */
class Watcher internal constructor(private val job: Job) {
    fun cancel() = job.cancel()
}

/**
 * Headless SRCS app: owns the node and services while online, persists pairings and messages in
 * [dataDir], and publishes [AppSnapshot]s. The iOS app is a thin SwiftUI layer over this class;
 * every command is fire-and-forget and safe to call from the main thread.
 */
class SrcsController(
    private val dataDir: String,
    privateKey: ByteArray,
    displayName: String,
    private val audio: AudioEngine,
    private val hooks: MediaHooks,
    private val nodeConfig: NodeConfig = NodeConfig(),
    private val callTimers: (CallTimer) -> Long = { it.ms },
    private val callStallMs: Long = CallManager.STALL_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val keyPair = KeyPair.fromPrivate(privateKey)
    private val log = LogBuffer(capacity = 300)
    private val trustStore: FileTrustStore
    private val messageStore: FileMessageStore
    private val lifecycle = Mutex()

    @Volatile private var name = displayName.trim().ifBlank { "iPhone" }.take(40)
    @Volatile private var stack: Stack? = null
    @Volatile private var autoConnect = false
    /** Set after someone tapped Disconnect: no automatic dialling until the user connects again. */
    @Volatile private var autoPaused = false
    @Volatile private var lastFix: GeoFix? = null
    private val busy = MutableStateFlow(false)

    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<AppSnapshot> = _snapshot.asStateFlow()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** Join walkie-talkies from paired phones without asking (group calls always ask). */
    var autoJoinWalkie: Boolean = true

    /** Short user-facing notices (toasts): "Busy", "Host lost — you are now hosting", … */
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    val deviceId: String get() = LocalIdentity(keyPair, name).deviceId.hex

    init {
        Files.mkdirs(dataDir)
        trustStore = FileTrustStore("$dataDir/peers.bin")
        messageStore = FileMessageStore("$dataDir/messages.bin")
        scope.launch { messageStore.version.collect { publish() } }
        scope.launch { trustStore.version.collect { publish() } }
        scope.launch { busy.collect { publish() } }
        scope.launch { log.lines.collect { publish() } }
        scope.launch { autoConnectLoop() }
    }

    private inner class Stack(identity: LocalIdentity) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val node = LinkNode(identity, trustStore, nodeConfig, log)
        val media = MediaCoordinator(node, audio, scope, hooks)
        val calls = CallManager(node, media, scope, timerMs = callTimers, stallMs = callStallMs)
        val groups = GroupManager(node, media, scope, isCallActive = { calls.state.value.phase.isLive }, autoJoin = { autoJoinWalkie })
        val messaging = MessagingService(node, messageStore, groups, scope)
        val location = LocationService(node, groups, scope)
        val dj = DjService(node, groups, media, audio, scope)

        fun watchAll() {
            scope.launch { node.linkInfos.collect { publish() } }
            scope.launch { node.peers.collect { publish() } }
            scope.launch { node.running.collect { publish() } }
            scope.launch { calls.state.collect { publish() } }
            scope.launch { calls.muted.collect { publish() } }
            scope.launch { groups.group.collect { publish() } }
            scope.launch { groups.ptt.collect { publish() } }
            scope.launch { groups.invites.collect { publish() } }
            scope.launch { location.sharing.collect { publish() } }
            scope.launch { dj.state.collect { publish() } }
            scope.launch { location.friends.collect { publish() } }
            scope.launch { location.activeSos.collect { publish() } }
            scope.launch { location.myFix.collect { publish() } }
            scope.launch {
                location.sosReceived.collect { a -> _notices.tryEmit("SOS from " + a.name + (if (a.note.isNotBlank()) ": " + a.note else "")) }
            }
            lastFix?.let { location.onFix(it) }
            scope.launch {
                groups.events.collect { e ->
                    when (e) {
                        is GroupEvent.Info -> _notices.tryEmit(e.message)
                        is GroupEvent.Ended -> _notices.tryEmit("${e.name}: ${e.reason}")
                        is GroupEvent.AutoJoined -> _notices.tryEmit("Joined ${e.hostName}'s walkie-talkie. Hold to talk.")
                    }
                }
            }
            scope.launch {
                calls.finished.collect { s ->
                    val r = s.endReason ?: return@collect
                    if (s.phase == CallPhase.FAILED || r.name !in setOf("HANGUP_LOCAL", "HANGUP_REMOTE")) _notices.tryEmit(r.label)
                }
            }
            scope.launch {
                node.incoming.collect { inc ->
                    if (inc.message !is AppMessage.LinkBye) return@collect
                    // The other phone tapped Disconnect: follow it, and do not dial back by ourselves.
                    autoPaused = true
                    _notices.tryEmit("${node.peerName(inc.from)} disconnected")
                    val g = groups.group.value
                    if (g != null && (g.host == inc.from || g.members.none { it.id != node.selfId && it.id != inc.from })) groups.leave()
                    delay(300)
                    if (node.peers.value.none { it.trusted && it.reachable && it.direct && it.id != inc.from }) node.disconnectAll()
                }
            }
            scope.launch {
                node.events.collect { e ->
                    when (e) {
                        is NodeEvent.Paired -> _notices.tryEmit("Paired with ${e.name}")
                        is NodeEvent.PairingFailed -> _notices.tryEmit("Pairing with ${e.remoteName} failed: ${e.reason}")
                        else -> {}
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    /** Start listening and linking (port 47700 TCP / 47701 UDP by default). */
    fun goOnline() { scope.launch { start() } }

    fun goOffline() { scope.launch { stop() } }

    suspend fun start(): Boolean = lifecycle.withLock {
        if (stack != null) return@withLock true
        busy.value = true
        try {
            val s = Stack(LocalIdentity(keyPair, name))
            s.node.start()
            s.watchAll()
            stack = s
            log.i(TAG, "online as $name")
            true
        } catch (e: Exception) {
            log.e(TAG, "could not go online: ${e::class.simpleName}: ${e.message}")
            _notices.tryEmit("Could not go online: ${e.message ?: e::class.simpleName}")
            false
        } finally {
            busy.value = false
            publish()
        }
    }

    suspend fun stop() = lifecycle.withLock {
        val s = stack ?: return@withLock
        busy.value = true
        try {
            stack = null
            if (s.calls.state.value.phase.isLive) s.calls.hangup()
            s.dj.stop()
            s.groups.leave()
            delay(300) // let goodbye messages go out
            s.node.stop()
            s.scope.cancel()
        } finally {
            busy.value = false
            publish()
        }
    }

    /** Stops everything; the controller cannot be used afterwards. */
    fun close() {
        scope.launch {
            stop()
            scope.cancel()
        }
    }

    /** TCP port actually in use while online (tests use port 0). */
    val tcpPort: Int get() = stack?.node?.tcpPort ?: -1

    // ------------------------------------------------------------------ linking

    /** Dial an SRCS device. On an Android host's Wi-Fi Direct network the host is [WifiJoin.HOST_ADDRESS]. */
    /**
     * One-tap disconnect: tells the connected phones (they drop the link too and do not dial back),
     * leaves the walkie-talkie or group call and closes the links. No automatic reconnecting until
     * the user connects again.
     */
    fun disconnectAll() {
        scope.launch {
            val s = stack ?: return@launch
            autoPaused = true
            val direct = s.node.peers.value.filter { it.trusted && it.reachable && it.direct }
            for (p in direct) s.node.send(p.id, AppMessage.LinkBye())
            if (s.calls.state.value.phase.isLive) s.calls.hangup()
            s.groups.leave()
            delay(400)
            s.node.disconnectAll()
            _notices.tryEmit(if (direct.isEmpty()) "Disconnected" else "Disconnected from ${direct.joinToString { it.name }}")
        }
    }

    /** Start a walkie-talkie with every paired phone in range; they join it automatically. */
    fun startWalkieTalkie() {
        val s = stack ?: return
        val peers = s.node.peers.value.filter { it.trusted && it.reachable }.map { it.id.hex }
        if (peers.isEmpty()) _notices.tryEmit("Connect to a phone first") else createGroup("Walkie-talkie", true, peers)
    }

    fun connect(host: String, port: Int) {
        autoPaused = false
        scope.launch {
            val s = stack ?: run { _notices.tryEmit("Go online first"); return@launch }
            val r = s.node.connect(host.trim(), port)
            if (r.isFailure) _notices.tryEmit("Could not connect to $host: ${r.exceptionOrNull()?.message ?: "error"}")
        }
    }

    fun connectToAndroidHost() = connect(WifiJoin.HOST_ADDRESS, nodeConfig.tcpPort)

    /** While online with no links, keep trying the Android host address (the iPhone joined its Wi-Fi). */
    fun setAutoConnect(enabled: Boolean) { autoConnect = enabled }

    private suspend fun autoConnectLoop() {
        while (scope.isActive) {
            delay(4_000)
            val s = stack ?: continue
            if (!autoConnect || autoPaused || s.node.linkInfos.value.isNotEmpty()) continue
            s.node.connect(WifiJoin.HOST_ADDRESS, nodeConfig.tcpPort, timeoutMs = 3_000)
        }
    }

    fun confirmPairing(linkId: Long, accept: Boolean) {
        scope.launch { stack?.node?.confirmPairing(linkId, accept) }
    }

    fun unpair(peerId: String) {
        scope.launch {
            val id = DeviceId.fromHex(peerId)
            val s = stack
            if (s != null) s.node.forget(id) else trustStore.remove(id)
        }
    }

    fun setDisplayName(newName: String) {
        name = newName.trim().ifBlank { name }.take(40)
        stack?.node?.displayName = name
        publish()
    }

    // ------------------------------------------------------------------ calls

    fun call(peerId: String) {
        val s = stack ?: run { _notices.tryEmit("Go online first"); return }
        if (!s.calls.call(DeviceId.fromHex(peerId))) _notices.tryEmit("Cannot call now")
    }

    fun answer() { stack?.calls?.accept() }
    fun decline() { stack?.calls?.reject() }
    fun hangUp() { stack?.calls?.hangup() }
    fun setMuted(muted: Boolean) {
        stack?.calls?.setMuted(muted)
        stack?.groups?.setMuted(muted)
    }

    // ------------------------------------------------------------------ messages

    fun sendMessage(peerId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            val to = DeviceId.fromHex(peerId)
            val s = stack
            if (s != null) {
                s.messaging.send(to, body)
            } else {
                // Offline: queue it; MessagingService sends pending messages once online.
                val me = LocalIdentity(keyPair, name)
                var b = body.take(2_000)
                while (b.encodeToByteArray().size > ProtocolLimits.MAX_TEXT_BYTES) b = b.dropLast(1)
                messageStore.insert(StoredMessage(DeviceId.random(), to, false, me.deviceId, name, to, true, b, currentTimeMillis(), status = MessageStatus.QUEUED))
            }
        }
    }

    fun sendGroupMessage(text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch { stack?.messaging?.sendToGroup(body) }
    }

    fun markRead(conversationId: String) {
        scope.launch { stack?.messaging?.markRead(DeviceId.fromHex(conversationId)) }
    }

    /** Conversation with a peer (or a group), oldest first. */
    fun messages(conversationId: String): List<MessageView> =
        messageStore.conversation(DeviceId.fromHex(conversationId)).map { m ->
            MessageView(m.messageId.hex, m.outgoing, m.authorName, m.body, m.sentAt, if (m.outgoing) statusLabel(m.status) else "")
        }

    fun deleteConversation(conversationId: String) = messageStore.deleteConversation(DeviceId.fromHex(conversationId))

    // ------------------------------------------------------------------ groups

    fun acceptInvite(groupId: String) { scope.launch { stack?.groups?.acceptInvite(DeviceId.fromHex(groupId)) } }
    fun declineInvite(groupId: String) { scope.launch { stack?.groups?.declineInvite(DeviceId.fromHex(groupId)) } }
    fun leaveGroup() { scope.launch { stack?.groups?.leave() } }
    fun pressTalk() { scope.launch { stack?.groups?.pressTalk() } }
    fun releaseTalk() { scope.launch { stack?.groups?.releaseTalk() } }

    /** Create a group with paired, reachable devices ([peerIds]); this phone hosts it. */
    fun createGroup(groupName: String, walkieTalkie: Boolean, peerIds: List<String>) {
        scope.launch {
            val r = stack?.groups?.create(groupName.ifBlank { "Group" }, if (walkieTalkie) GroupMode.PTT else GroupMode.CALL, peerIds.map { DeviceId.fromHex(it) })
            if (r?.isFailure == true) _notices.tryEmit(r.exceptionOrNull()?.message ?: "Could not create the group")
        }
    }

    // ------------------------------------------------------------------ location and SOS

    /** New GPS fix from the platform (CoreLocation on iOS). */
    fun onLocation(lat: Double, lon: Double, accuracyM: Double, atMillis: Long) {
        val fix = GeoFix(lat, lon, accuracyM.toFloat(), atMillis)
        lastFix = fix
        val s = stack
        if (s != null) s.location.onFix(fix) else publish()
    }

    /** Opt-in: share with all reachable paired phones and/or the current group for [minutes]. */
    fun startSharing(toPaired: Boolean, toGroup: Boolean, minutes: Int) {
        val s = stack ?: run { _notices.tryEmit("Go online first"); return }
        s.location.startSharing(toPaired, toGroup, minutes.coerceIn(1, 24 * 60) * 60_000L)
    }

    fun stopSharing() { stack?.location?.stopSharing() }

    fun sendSos(note: String) {
        scope.launch {
            val s = stack ?: run { _notices.tryEmit("Go online first: SOS needs at least one phone in range"); return@launch }
            val n = s.location.sendSos(note)
            _notices.tryEmit(if (n > 0) "SOS sent to $n phone(s)" else "SOS queued: no paired phone in range yet")
        }
    }

    fun dismissSos(alertId: String) { stack?.location?.dismissSos(DeviceId.fromHex(alertId)) }

    // ------------------------------------------------------------------ DJ Mode

    /** Group host: start DJ Mode for the group. */
    fun djStart() {
        scope.launch { stack?.dj?.start()?.onFailure { _notices.tryEmit(it.message ?: "Could not start DJ Mode") } }
    }

    /** Group host: play a song decoded by the platform (the service closes [source]). */
    fun djPlay(title: String, artist: String, durationMs: Long, source: MusicSource) {
        scope.launch {
            val dj = stack?.dj
            if (dj == null) { source.close(); return@launch }
            dj.play(DjTrackInfo(DeviceId.random(), title.take(80), artist.take(80), durationMs), source)
        }
    }

    fun djPauseResume() {
        scope.launch {
            val dj = stack?.dj ?: return@launch
            if (dj.state.value.playing) dj.pause() else dj.resume()
        }
    }

    fun djStop() { scope.launch { stack?.dj?.stop() } }
    fun djRequest(text: String) { scope.launch { stack?.dj?.request(text) } }
    fun djClearRequests() {
        val dj = stack?.dj ?: return
        dj.state.value.requests.forEach { dj.clearRequest(it) }
    }

    // ------------------------------------------------------------------ offline maps

    /** Map areas live in <dataDir>/maps. */
    val maps: MapAreaManager by lazy { MapAreaManager("$dataDir/maps", scope) { publish() } }

    fun planMapArea(name: String, lat: Double, lon: Double, radiusKm: Double) = maps.planArea(name, lat, lon, radiusKm)
    fun startMapDownload() = maps.startDownload()
    fun cancelMapDownload() = maps.cancelDownload()
    fun deleteMapArea(path: String) = maps.delete(path)
    fun importMapArea(sourcePath: String, name: String): String = maps.importFile(sourcePath, name)

    /** Best area for a position (the most detailed one that contains it), or null. */
    fun mapAreaFor(lat: Double, lon: Double): MapRegion? = maps.regions.bestFor(lat, lon)?.takeIf { it.contains(lat, lon) }

    // ------------------------------------------------------------------ observing (Swift-friendly)

    fun watch(onChange: (AppSnapshot) -> Unit): Watcher = Watcher(scope.launch { snapshot.collect { onChange(it) } })

    fun watchNotices(onNotice: (String) -> Unit): Watcher = Watcher(scope.launch { notices.collect { onNotice(it) } })

    // ------------------------------------------------------------------ snapshot

    private fun emptySnapshot() = AppSnapshot(
        online = false, busy = false, displayName = name, deviceId = LocalIdentity(keyPair, name).deviceId.hex,
        fingerprint = LocalIdentity(keyPair, name).fingerprint, linkCount = 0, pairings = emptyList(), peers = emptyList(),
        call = null, group = null, invites = emptyList(), messagesVersion = 0, recentLog = emptyList(), radar = emptyList(),
        location = LocationView(false, false, false, 0, 0, false, 0.0, 0.0, 0), friends = emptyList(), sos = emptyList(),
        mapAreas = emptyList(), mapDownload = MapDownloadView("IDLE"), dj = NO_DJ,
    )

    private fun publish() {
        _snapshot.value = try {
            build()
        } catch (e: Exception) {
            log.e(TAG, "snapshot failed: ${e::class.simpleName}")
            return
        }
    }

    private fun build(): AppSnapshot {
        val s = stack
        val me = LocalIdentity(keyPair, name)
        val links = s?.node?.linkInfos?.value ?: emptyList()
        val status = s?.node?.peers?.value?.associateBy { it.id } ?: emptyMap()
        val allMessages = messageStore.all()
        val peers = trustStore.all().map { t ->
            val st = status[t.id]
            val conv = allMessages.filter { it.conversationId == t.id && !it.isGroup }
            PeerView(
                id = t.id.hex, name = st?.name ?: t.name, reachable = st?.reachable == true, direct = st?.direct == true,
                hops = if (st?.direct == false) st.hops ?: 0 else 0,
                unread = conv.count { !it.outgoing && !it.read },
                lastMessage = conv.maxByOrNull { it.sentAt }?.body?.take(60) ?: "",
            )
        }.sortedWith(compareByDescending<PeerView> { it.reachable }.thenBy { it.name.lowercase() })

        val call = s?.calls?.state?.value?.takeIf { it.phase != CallPhase.IDLE }?.let { c ->
            val peer = c.peer
            CallView(
                phase = c.phase.name, peerId = peer?.hex ?: "", peerName = peer?.let { s.node.peerName(it) } ?: "",
                outgoing = c.outgoing, activeSince = c.activeSince ?: 0, muted = s.calls.muted.value,
                status = callStatus(c.phase, c.outgoing, c.endReason?.label), incomingRinging = c.phase == CallPhase.INCOMING_RINGING,
                live = c.phase.isLive,
            )
        }

        val group = s?.groups?.group?.value?.let { g ->
            val ptt = s.groups.ptt.value
            GroupView(
                id = g.groupId.hex, name = g.name, isPtt = g.mode == GroupMode.PTT, isHost = g.role == GroupRole.HOST,
                status = g.status.name,
                members = g.members.map { m -> MemberView(m.id.hex, m.name, m.id == g.host, m.id == me.deviceId, ptt.holder == m.id) },
                talker = ptt.holder?.let { h -> g.members.firstOrNull { it.id == h }?.name ?: s.node.peerName(h) } ?: "",
                iAmTalking = ptt.talking, requesting = ptt.requesting, denied = ptt.denied,
            )
        }

        val pairingLinks = links.filter { !it.trusted }
        val loc = s?.location
        val myFix = loc?.myFix?.value ?: lastFix
        val friendLocs = loc?.friends?.value ?: emptyList()
        val now = currentTimeMillis()
        fun distText(lat: Double, lon: Double): String = myFix?.let {
            val d = Geo.distanceM(it.lat, it.lon, lat, lon)
            Geo.formatDistance(d) + " " + Geo.compass(Geo.bearingDeg(it.lat, it.lon, lat, lon))
        } ?: ""
        val geoById = friendLocs.associateBy { it.id.hex }
        val radar = RadarLayout.place(
            peers.filter { it.reachable }.map { p ->
                val f = geoById[p.id]
                RadarEntry(
                    p.id, p.name, if (p.direct) RadarRing.LINKED else RadarRing.RELAYED, peerId = p.id,
                    bearingDeg = if (f != null && myFix != null) Geo.bearingDeg(myFix.lat, myFix.lon, f.lat, f.lon) else null,
                    distanceM = if (f != null && myFix != null) Geo.distanceM(myFix.lat, myFix.lon, f.lat, f.lon) else null,
                )
            } + pairingLinks.map { RadarEntry("link:" + it.linkId, it.remoteName, RadarRing.NEARBY, pairingLinkId = it.linkId) },
        )
        val sharing = loc?.sharing?.value
        val locationView = LocationView(
            sharing?.active == true, sharing?.toPaired == true, sharing?.toGroup == true, sharing?.until ?: 0, sharing?.recipients ?: 0,
            myFix != null, myFix?.lat ?: 0.0, myFix?.lon ?: 0.0, myFix?.accuracyM?.toInt() ?: 0,
        )
        val friends = friendLocs.map { f ->
            FriendView(f.id.hex, f.name, f.lat, f.lon, f.accuracyM, distText(f.lat, f.lon), ((now - f.fixAt) / 1000).coerceAtLeast(0), f.viaGroup)
        }
        val sos = (loc?.activeSos?.value ?: emptyList()).map { a ->
            SosView(a.alertId.hex, a.name, a.note, a.fix != null, a.fix?.lat ?: 0.0, a.fix?.lon ?: 0.0, a.fix?.let { distText(it.lat, it.lon) } ?: "", a.receivedAt)
        }

        return AppSnapshot(
            online = s != null, busy = busy.value, displayName = name, deviceId = me.deviceId.hex, fingerprint = me.fingerprint,
            linkCount = links.count { it.trusted },
            pairings = links.filter { !it.trusted }.map { PairingView(it.linkId, it.remoteName, it.sas, it.localConfirmed) },
            peers = peers, call = call, group = group,
            invites = s?.groups?.invites?.value?.map { InviteView(it.groupId.hex, it.name, it.fromName, it.mode == GroupMode.PTT) } ?: emptyList(),
            messagesVersion = messageStore.version.value,
            recentLog = log.lines.value.takeLast(80).map { "${it.level.name.first()} ${it.tag}: ${it.message}" },
            radar = radar, location = locationView, friends = friends, sos = sos,
            mapAreas = maps.list.value, mapDownload = maps.download.value,
            dj = s?.dj?.state?.value?.let { d ->
                DjView(
                    d.role.name, d.djName, d.track?.title ?: "", d.track?.artist ?: "", d.playing, d.ended, d.positionMs,
                    d.track?.durationMs ?: 0, d.listeners, d.requests.map { it.name + ": " + it.text },
                )
            } ?: NO_DJ,
        )
    }

    private fun callStatus(phase: CallPhase, outgoing: Boolean, endLabel: String?): String = when (phase) {
        CallPhase.OUTGOING_INVITE -> "Calling…"
        CallPhase.REMOTE_RINGING -> "Ringing…"
        CallPhase.INCOMING_RINGING -> "Incoming call"
        CallPhase.CONNECTING -> "Connecting…"
        CallPhase.ACTIVE -> "Connected"
        CallPhase.RECONNECTING -> "Reconnecting…"
        CallPhase.ENDING -> "Ending…"
        CallPhase.ENDED, CallPhase.FAILED -> endLabel ?: "Call ended"
        CallPhase.IDLE -> ""
    }

    private fun statusLabel(s: MessageStatus) = when (s) {
        MessageStatus.QUEUED -> "Waiting for device"
        MessageStatus.SENT -> "Sending"
        MessageStatus.RETRYING -> "Retrying"
        MessageStatus.DELIVERED -> "Delivered"
        MessageStatus.READ -> "Read"
        MessageStatus.FAILED -> "Not delivered"
        MessageStatus.RECEIVED -> ""
    }

    companion object {
        private const val TAG = "app"
        private val NO_DJ = DjView(DjRole.NONE.name, "", "", "", false, false, 0, 0, 0, emptyList())

        /** Swift entry point (Swift cannot use Kotlin default arguments). */
        fun create(dataDir: String, privateKey: ByteArray, displayName: String, audio: AudioEngine, hooks: MediaHooks): SrcsController =
            SrcsController(dataDir, privateKey, displayName, audio, hooks)

        /** A new random X25519 private key (the iOS app stores it in the Keychain). */
        fun generatePrivateKey(): ByteArray = ai.paai.link.core.crypto.Primitives.generateKeyPair().privateKeyCopy()
    }
}
