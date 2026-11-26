package ai.paai.link.ui

import ai.paai.link.LinkApplication
import ai.paai.link.audio.AudioRoute
import ai.paai.link.core.call.CallState
import ai.paai.link.core.group.GroupEvent
import ai.paai.link.core.group.GroupInviteInfo
import ai.paai.link.core.group.GroupState
import ai.paai.link.core.media.VoiceSession
import ai.paai.link.core.node.LinkInfo
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.node.PeerStatus
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.ptt.PttState
import ai.paai.link.core.routing.Route
import ai.paai.link.core.util.DeviceId
import ai.paai.link.data.MessageEntity
import ai.paai.link.data.PeerEntity
import ai.paai.link.data.Settings
import ai.paai.link.wifidirect.P2pPeer
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConversationRow(
    val id: DeviceId,
    val title: String,
    val isGroup: Boolean,
    val last: MessageEntity,
    val unread: Int,
)

/** UI-facing state and actions. Owns no long-lived state itself; that lives in [ai.paai.link.AppContainer]. */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkViewModel(app: Application) : AndroidViewModel(app) {
    val c = (app as LinkApplication).container
    private fun <T> Flow<T>.state(initial: T): StateFlow<T> = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val stack = c.stack
    val online: StateFlow<Boolean> = c.stack.map { it != null }.state(false)
    val transitioning = c.transitioning
    val settings: StateFlow<Settings> = c.settings
    val fingerprint = c.fingerprint
    val wifi = c.wifi.state
    val aware = c.aware.state
    val sharing = c.stack.flatMapLatest { it?.location?.sharing ?: flowOf(ai.paai.link.core.location.SharingState()) }.state(ai.paai.link.core.location.SharingState())
    val friendLocations = c.stack.flatMapLatest { it?.location?.friends ?: flowOf(emptyList()) }.state(emptyList())
    val myFix = c.stack.flatMapLatest { it?.location?.myFix ?: flowOf(null) }.state(null)
    val activeSos = c.stack.flatMapLatest { it?.location?.activeSos ?: flowOf(emptyList()) }.state(emptyList())
    val gpsEnabled: Boolean get() = c.gps.gpsEnabled

    fun startSharing(toPaired: Boolean, toGroup: Boolean, minutes: Int) {
        val s = c.stack.value ?: return toast("Go online first")
        if (!c.gps.hasPermission()) return toast("Location permission is needed")
        s.location.startSharing(toPaired, toGroup, minutes * 60_000L)
        if (!c.gps.gpsEnabled) toast("Turn on Location in Android settings so GPS can find you")
    }

    fun stopSharing() { c.stack.value?.location?.stopSharing() }

    fun sendSos(note: String) = launch {
        if (c.stack.value == null) return@launch toast("Go online first: SOS needs at least one phone in range")
        toast("Getting your position…")
        val n = c.sendSos(note)
        toast(if (n > 0) "SOS sent to $n phone(s)" else "SOS queued: no paired phone in range yet")
    }

    fun dismissSos(id: ai.paai.link.core.util.DeviceId) { c.stack.value?.location?.dismissSos(id) }
    val logs = c.log.lines
    val route = c.audioRouter.state

    val links: StateFlow<List<LinkInfo>> = c.stack.flatMapLatest { it?.node?.linkInfos ?: flowOf(emptyList()) }.state(emptyList())
    val peerStatus: StateFlow<List<PeerStatus>> = c.stack.flatMapLatest { it?.node?.peers ?: flowOf(emptyList()) }.state(emptyList())
    val routes: StateFlow<List<Route>> = c.stack.flatMapLatest { it?.node?.routes ?: flowOf(emptyList()) }.state(emptyList())
    val paired: StateFlow<List<PeerEntity>> = c.db.peers().observe().state(emptyList())
    val call: StateFlow<CallState> = c.stack.flatMapLatest { it?.calls?.state ?: flowOf(CallState()) }.state(CallState())
    val callSession: StateFlow<VoiceSession?> = c.stack.flatMapLatest { it?.calls?.session ?: flowOf(null) }.state(null)
    val muted: StateFlow<Boolean> = c.stack.flatMapLatest { it?.calls?.muted ?: flowOf(false) }.state(false)
    val group: StateFlow<GroupState?> = c.stack.flatMapLatest { it?.groups?.group ?: flowOf(null) }.state(null)
    val groupSession: StateFlow<VoiceSession?> = c.stack.flatMapLatest { it?.groups?.session ?: flowOf(null) }.state(null)
    val invites: StateFlow<List<GroupInviteInfo>> = c.stack.flatMapLatest { it?.groups?.invites ?: flowOf(emptyList()) }.state(emptyList())
    val ptt: StateFlow<PttState> = c.stack.flatMapLatest { it?.groups?.ptt ?: flowOf(PttState()) }.state(PttState())
    val recentCalls = c.db.calls().observeRecent().state(emptyList())

    val conversations: StateFlow<List<ConversationRow>> = combine(
        c.db.messages().observeLatestPerConversation(),
        c.db.messages().observeUnread(),
        c.db.peers().observe(),
        c.db.groupNames().observe(),
    ) { latest, unread, peers, groups ->
        val unreadMap = unread.associate { it.conversationId to it.n }
        val names = peers.associate { it.id to it.displayName } + groups.associate { it.id to it.name }
        latest.distinctBy { it.conversationId }.map {
            ConversationRow(DeviceId.fromHex(it.conversationId), names[it.conversationId] ?: if (it.isGroup) "Group" else it.conversationId.take(8), it.isGroup, it, unreadMap[it.conversationId] ?: 0)
        }
    }.state(emptyList())

    fun conversation(id: DeviceId): Flow<List<MessageEntity>> = c.db.messages().observeConversation(id.hex)

    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Short user-facing notices (pairing results, group changes, errors). */
    val toasts: SharedFlow<String> = _toasts

    init {
        viewModelScope.launch {
            c.stack.flatMapLatest { s ->
                if (s == null) emptyFlow() else merge(
                    s.node.events.map { e ->
                        when (e) {
                            is NodeEvent.Paired -> "Paired with ${e.name}"
                            is NodeEvent.PairingFailed -> "Pairing with ${e.remoteName} failed: ${e.reason}"
                            else -> null
                        }
                    },
                    s.groups.events.map { e ->
                        when (e) {
                            is GroupEvent.Ended -> "${e.name}: ${e.reason}"
                            is GroupEvent.Info -> e.message
                        }
                    },
                )
            }.collect { msg -> if (msg != null) _toasts.tryEmit(msg) }
        }
    }

    private fun toast(s: String) { _toasts.tryEmit(s) }

    /** Short message at the bottom of the screen. */
    fun notify(s: String) = toast(s)

    /** True if a downloaded map area covers the position (then the in-app map can show it). */
    fun hasMapFor(lat: Double, lon: Double): Boolean = c.maps.list.value.any { it.contains(lat, lon) }
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // ------------------------------------------------------------------ online

    fun goOnline() = launch { c.goOnline().onFailure { toast("Could not go online: ${it.message}") } }
    fun goOffline() = launch { c.goOffline() }

    // ------------------------------------------------------------------ Wi-Fi Direct and links

    fun scan() {
        c.wifi.discover()
        c.startAwareIfAllowed()
    }
    fun stopScan() = c.wifi.stopDiscovery()
    fun connectP2p(p: P2pPeer) = c.wifi.connect(p)
    fun hostGroup() = c.wifi.createGroup()
    fun leaveP2p() = c.wifi.disconnect()
    fun clearWifiError() = c.wifi.clearError()

    fun connectByAddress(address: String) = launch {
        val node = c.stack.value?.node ?: return@launch toast("Go online first")
        val (host, port) = address.trim().split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: node.config.tcpPort) }
        if (host.isBlank()) return@launch
        node.connect(host, port).onSuccess { toast("Connected") }.onFailure { toast("Could not connect: ${it.message ?: it.javaClass.simpleName}") }
    }

    fun confirmPairing(linkId: Long, accept: Boolean) = launch { c.stack.value?.node?.confirmPairing(linkId, accept) }
    fun disconnect(peer: DeviceId) = launch { c.stack.value?.node?.disconnect(peer) }
    fun unpair(peer: DeviceId) = launch {
        val node = c.stack.value?.node
        if (node != null) node.forget(peer) else c.trustStore.remove(peer)
    }
    fun rename(peer: DeviceId, name: String) = launch { c.db.peers().rename(peer.hex, name.trim().take(40).ifBlank { null }) }

    // ------------------------------------------------------------------ calls

    fun call(peer: DeviceId) {
        val s = c.stack.value ?: return toast("Go online first")
        when {
            !s.node.isReachable(peer) -> toast("${s.node.peerName(peer)} is not reachable right now")
            !s.calls.call(peer) -> toast("Finish the current call or group first")
        }
    }
    fun accept() = c.stack.value?.calls?.accept()
    fun reject() = c.stack.value?.calls?.reject()
    fun hangup() = c.stack.value?.calls?.hangup()
    fun setMuted(m: Boolean) = c.stack.value?.calls?.setMuted(m)
    fun selectRoute(r: AudioRoute) = c.audioRouter.select(r)

    // ------------------------------------------------------------------ groups / PTT

    fun createGroup(name: String, mode: GroupMode, members: List<DeviceId>) = launch {
        val s = c.stack.value ?: return@launch toast("Go online first")
        s.groups.create(name, mode, members).onFailure { toast(it.message ?: "Could not create group") }
    }
    fun acceptInvite(id: DeviceId) = launch { c.stack.value?.groups?.acceptInvite(id)?.onFailure { toast(it.message ?: "Could not join") } }
    fun declineInvite(id: DeviceId) = launch { c.stack.value?.groups?.declineInvite(id) }
    fun leaveGroup() = launch { c.stack.value?.groups?.leave() }
    fun kick(id: DeviceId) = launch { c.stack.value?.groups?.kick(id) }
    fun inviteToGroup(id: DeviceId) = launch { c.stack.value?.groups?.invite(id) }
    fun pressTalk() = launch { c.stack.value?.groups?.pressTalk() }
    fun releaseTalk() = launch { c.stack.value?.groups?.releaseTalk() }
    fun setGroupMuted(m: Boolean) = c.stack.value?.groups?.setMuted(m)
    fun setMemberMuted(id: DeviceId, m: Boolean) = c.stack.value?.groups?.setMemberMuted(id, m)

    // ------------------------------------------------------------------ messages

    fun send(to: DeviceId, body: String) = launch { if (body.isNotBlank()) c.sendMessage(to, body) }
    fun sendGroup(body: String) = launch {
        if (body.isBlank()) return@launch
        if (c.stack.value?.messaging?.sendToGroup(body) == null) toast("Group chat is available while the group is active")
    }
    fun markRead(id: DeviceId) = launch { c.stack.value?.messaging?.markRead(id) }
    fun retry(id: DeviceId) = launch { c.stack.value?.messaging?.retry(id) }
    fun deleteConversation(id: DeviceId) = launch { c.deleteConversation(id) }
    fun setOpenConversation(id: DeviceId?) { c.openConversation.value = id }

    // ------------------------------------------------------------------ settings

    fun updateSettings(transform: (Settings) -> Settings) = launch { c.settingsRepo.update(transform) }
    fun clearCallLog() = launch { c.db.calls().clear() }
}
