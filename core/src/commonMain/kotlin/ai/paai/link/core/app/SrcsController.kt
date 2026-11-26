package ai.paai.link.core.app

import ai.paai.link.core.call.CallManager
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.call.CallTimer
import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.group.GroupEvent
import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.group.GroupRole
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
    private val busy = MutableStateFlow(false)

    private val _snapshot = MutableStateFlow(emptySnapshot())
    val snapshot: StateFlow<AppSnapshot> = _snapshot.asStateFlow()
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 32)
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
        val groups = GroupManager(node, media, scope, isCallActive = { calls.state.value.phase.isLive })
        val messaging = MessagingService(node, messageStore, groups, scope)

        fun watchAll() {
            scope.launch { node.linkInfos.collect { publish() } }
            scope.launch { node.peers.collect { publish() } }
            scope.launch { node.running.collect { publish() } }
            scope.launch { calls.state.collect { publish() } }
            scope.launch { calls.muted.collect { publish() } }
            scope.launch { groups.group.collect { publish() } }
            scope.launch { groups.ptt.collect { publish() } }
            scope.launch { groups.invites.collect { publish() } }
            scope.launch {
                groups.events.collect { e ->
                    when (e) {
                        is GroupEvent.Info -> _notices.tryEmit(e.message)
                        is GroupEvent.Ended -> _notices.tryEmit("${e.name}: ${e.reason}")
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
    fun connect(host: String, port: Int) {
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
            if (!autoConnect || s.node.linkInfos.value.isNotEmpty()) continue
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

    // ------------------------------------------------------------------ observing (Swift-friendly)

    fun watch(onChange: (AppSnapshot) -> Unit): Watcher = Watcher(scope.launch { snapshot.collect { onChange(it) } })

    fun watchNotices(onNotice: (String) -> Unit): Watcher = Watcher(scope.launch { notices.collect { onNotice(it) } })

    // ------------------------------------------------------------------ snapshot

    private fun emptySnapshot() = AppSnapshot(
        online = false, busy = false, displayName = name, deviceId = LocalIdentity(keyPair, name).deviceId.hex,
        fingerprint = LocalIdentity(keyPair, name).fingerprint, linkCount = 0, pairings = emptyList(), peers = emptyList(),
        call = null, group = null, invites = emptyList(), messagesVersion = 0, recentLog = emptyList(),
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

        return AppSnapshot(
            online = s != null, busy = busy.value, displayName = name, deviceId = me.deviceId.hex, fingerprint = me.fingerprint,
            linkCount = links.count { it.trusted },
            pairings = links.filter { !it.trusted }.map { PairingView(it.linkId, it.remoteName, it.sas, it.localConfirmed) },
            peers = peers, call = call, group = group,
            invites = s?.groups?.invites?.value?.map { InviteView(it.groupId.hex, it.name, it.fromName, it.mode == GroupMode.PTT) } ?: emptyList(),
            messagesVersion = messageStore.version.value,
            recentLog = log.lines.value.takeLast(80).map { "${it.level.name.first()} ${it.tag}: ${it.message}" },
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

        /** Swift entry point (Swift cannot use Kotlin default arguments). */
        fun create(dataDir: String, privateKey: ByteArray, displayName: String, audio: AudioEngine, hooks: MediaHooks): SrcsController =
            SrcsController(dataDir, privateKey, displayName, audio, hooks)

        /** A new random X25519 private key (the iOS app stores it in the Keychain). */
        fun newPrivateKey(): ByteArray = ai.paai.link.core.crypto.Primitives.generateKeyPair().privateKeyCopy()
    }
}
