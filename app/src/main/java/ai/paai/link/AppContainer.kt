package ai.paai.link

import ai.paai.link.audio.AndroidAudioEngine
import ai.paai.link.audio.AudioRouter
import ai.paai.link.core.call.CallManager
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.call.EndReason
import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.dj.DjService
import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.location.Geo
import ai.paai.link.core.location.LocationService
import ai.paai.link.location.AndroidGps
import ai.paai.link.maps.MapsController
import ai.paai.link.update.UpdateManager
import kotlinx.coroutines.flow.MutableSharedFlow
import ai.paai.link.wifidirect.AutoConnector
import ai.paai.link.core.group.GroupEvent
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.media.MediaCoordinator
import ai.paai.link.core.media.MediaHooks
import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.messaging.MessagingConfig
import ai.paai.link.core.messaging.MessagingService
import ai.paai.link.core.messaging.StoredMessage
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.node.LogBuffer
import ai.paai.link.core.node.LogLevel
import ai.paai.link.core.node.NodeConfig
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.util.DeviceId
import ai.paai.link.data.CallLogEntity
import ai.paai.link.data.GroupNameEntity
import ai.paai.link.data.LinkDatabase
import ai.paai.link.data.RoomMessageStore
import ai.paai.link.data.RoomTrustStore
import ai.paai.link.data.Settings
import ai.paai.link.data.SettingsRepository
import ai.paai.link.security.KeystoreIdentity
import ai.paai.link.service.LinkService
import ai.paai.link.service.Notifications
import ai.paai.link.wifiaware.WifiAwareController
import ai.paai.link.wifidirect.WifiDirectController
import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * Application-wide object graph (manual dependency injection). Long-lived state lives here rather
 * than in activities, so a call survives configuration changes and the activity going away.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val log = LogBuffer(capacity = 500) { line ->
        val prio = when (line.level) {
            LogLevel.DEBUG -> Log.DEBUG; LogLevel.INFO -> Log.INFO; LogLevel.WARN -> Log.WARN; LogLevel.ERROR -> Log.ERROR
        }
        Log.println(prio, "PAAI/${line.tag}", line.message)
    }
    val db = LinkDatabase.create(app)
    val settingsRepo = SettingsRepository(app)
    val settings: StateFlow<Settings> = settingsRepo.settings.stateIn(scope, SharingStarted.Eagerly, Settings())
    val trustStore = RoomTrustStore(db.peers())
    val messageStore = RoomMessageStore(db.messages())
    val audioRouter = AudioRouter(app)
    private val audioEngine = AndroidAudioEngine()
    val notifications = Notifications(app)

    private val _stack = MutableStateFlow<LinkStack?>(null)
    /** The running network stack, or null while offline. */
    val stack: StateFlow<LinkStack?> = _stack.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val transitioning: StateFlow<Boolean> = _busy.asStateFlow()
    private val lifecycleLock = Mutex()

    /** Conversation currently on screen (suppresses its notifications). */
    val openConversation = MutableStateFlow<DeviceId?>(null)
    /** True while the activity is in the foreground. */
    val appVisible = MutableStateFlow(false)

    private var identityKey: KeyPair? = null
    private val _fingerprint = MutableStateFlow<String?>(null)
    val fingerprint: StateFlow<String?> = _fingerprint.asStateFlow()

    val wifi = WifiDirectController(
        app, log,
        onGroupFormed = { owner, isOwner -> if (!isOwner) dialOwner(owner) },
        onGroupRemoved = { scope.launch { _stack.value?.node?.disconnectAll() } },
        onBusy = {
            // Wi-Fi Direct is the link every Android phone has; never let Wi-Fi Aware block it.
            if (aware.state.value.running) {
                aware.stop()
                log.w("app", "Wi-Fi Aware turned off: this phone cannot use it together with Wi-Fi Direct")
                awareConflict.value = true
                true
            } else false
        },
    )

    /** Set when this phone could not run Wi-Fi Aware and Wi-Fi Direct together. */
    val awareConflict = MutableStateFlow(false)

    /** Wi-Fi Aware: direct phone-to-phone links without a group owner (Android 10+, hardware dependent). */
    val aware = WifiAwareController(
        app, log,
        tcpPort = { _stack.value?.node?.tcpPort?.takeIf { it > 0 } ?: 47700 },
        onPeerAddress = { host -> dialAware(host) },
    )

    /** GPS, on only while sharing, sending an SOS, or viewing friends' positions with the app open. */
    val gps = AndroidGps(app, log) { fix -> _stack.value?.location?.onFix(fix) }
    private val sosPending = MutableStateFlow(false)
    /** True while the offline map or map download screen is open (they show this phone's position). */
    val mapOpen = MutableStateFlow(false)
    val maps = MapsController(app, log, scope)
    /** Optional check for a newer SRCS on GitHub (the only other internet use besides maps). */
    val updates = UpdateManager(app, log, scope)
    /** Short messages for the user that do not come from the network stack ("Kash disconnected"). */
    val notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Reconnects to phones this phone was connected to before. */
    val autoConnect = AutoConnector(
        app, log, scope, wifi,
        enabled = { settings.value.autoConnect && _stack.value != null },
        canScan = { hasPermission(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION) },
    )

    init {
        scope.launch {
            kotlinx.coroutines.flow.combine(_stack, appVisible, sosPending, mapOpen) { s, visible, sos, map -> listOf(s, visible, sos, map) }
                .flatMapLatest { (s, visible, sos, map) ->
                    s as LinkStack?; visible as Boolean; sos as Boolean; map as Boolean
                    if (s == null) flowOf(visible && map)
                    else kotlinx.coroutines.flow.combine(s.location.sharing, s.location.friends) { sh, friends ->
                        sh.active || sos || (visible && (map || friends.isNotEmpty()))
                    }
                }
                .distinctUntilChanged()
                .collect { wanted -> withContext(Dispatchers.Main) { gps.setWanted(wanted) } }
        }
        scope.launch {
            _stack.flatMapLatest { s -> s?.location?.sharing?.map { it.active } ?: flowOf(false) }.distinctUntilChanged().collect { on ->
                if (_stack.value != null) LinkService.setLocationSharing(app, on)
            }
        }
        scope.launch(Dispatchers.IO) {
            val (kp, created) = KeystoreIdentity.loadOrCreate(app)
            identityKey = kp
            _fingerprint.value = LocalIdentity(kp, "").fingerprint
            if (created) log.i("identity", "new device identity created")
        }
        scope.launch {
            settings.map { it.relayEnabled }.distinctUntilChanged().collect { applyRelayPolicy() }
        }
        // Remember the phones of a Wi-Fi Direct group once a paired SRCS link runs over it.
        scope.launch {
            kotlinx.coroutines.flow.combine(wifi.state.map { it.group }, _stack.flatMapLatest { it?.node?.linkInfos ?: flowOf(emptyList()) }) { g, links -> g to links }
                .collect { (g, links) ->
                    if (g.formed && g.members.isNotEmpty() && links.any { it.trusted }) {
                        autoConnect.remember(g.members)
                        autoConnect.resume() // connected again: reconnect by itself if the link drops later
                    }
                }
        }
    }

    // ------------------------------------------------------------------ online / offline

    suspend fun goOnline(): Result<Unit> = lifecycleLock.withLock {
        if (_stack.value != null) return Result.success(Unit)
        _busy.value = true
        try {
            val kp = identityKey ?: withContext(Dispatchers.IO) { KeystoreIdentity.loadOrCreate(app).first.also { identityKey = it } }
            val name = settings.value.displayName.ifBlank { android.os.Build.MODEL ?: "Phone" }
            val stack = LinkStack(this, LocalIdentity(kp, name), audioEngine)
            stack.start()
            _stack.value = stack
            LinkService.start(app)
            wifi.register()
            autoConnect.start()
            startAwareIfAllowed()
            registerBattery()
            applyRelayPolicy()
            settingsRepo.update { it.copy(wasOnline = true) }
            Result.success(Unit)
        } catch (e: Exception) {
            log.e("app", "could not go online: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        } finally {
            _busy.value = false
        }
    }

    /** User chose to go offline: also stop reconnecting automatically on the next app start. */
    suspend fun goOffline() {
        settingsRepo.update { it.copy(wasOnline = false) }
        lifecycleLock.withLock { goOfflineLocked() }
    }

    private suspend fun goOfflineLocked() {
        val s = _stack.value ?: return
        _busy.value = true
        try {
            autoConnect.stop()
            wifi.unregister()
            aware.stop()
            unregisterBattery()
            _stack.value = null
            s.stop()
            LinkService.stop(app)
            notifications.cancelAll()
        } finally {
            _busy.value = false
        }
    }

    /**
     * One-tap disconnect: tells the connected phones (so they drop the link too and do not dial
     * back), leaves any walkie-talkie or group call and leaves the Wi-Fi Direct group.
     */
    suspend fun disconnectAll() {
        val s = _stack.value ?: return
        autoConnect.pause()
        val direct = s.node.peers.value.filter { it.trusted && it.reachable && it.direct }
        for (p in direct) s.node.send(p.id, AppMessage.LinkBye())
        if (s.calls.state.value.phase.isLive) s.calls.hangup()
        s.groups.leave()
        delay(400) // let the goodbyes go out before the radio link drops
        s.node.disconnectAll() // closes the links and stops the node from dialling them again
        withContext(Dispatchers.Main) { wifi.disconnect() }
        log.i("app", "disconnected from ${direct.size} phone(s) on request")
    }

    /** The other phone tapped Disconnect: drop the link here too and do not reconnect by itself. */
    internal fun onPeerBye(s: LinkStack, from: DeviceId) {
        autoConnect.pause()
        val name = s.node.peerName(from)
        notices.tryEmit("$name disconnected")
        // No walkie-talkie left to talk in: end it here too instead of keeping an empty channel.
        val g = s.groups.group.value
        if (g != null && (g.host == from || g.members.none { it.id != s.node.selfId && it.id != from })) {
            scope.launch { s.groups.leave() }
        }
        if (!appVisible.value) notifications.showInfo("$name disconnected", "Tap Scan when you want to connect again.")
        scope.launch {
            delay(600)
            val stillLinked = s.node.peers.value.any { it.trusted && it.reachable && it.direct && it.id != from }
            if (!stillLinked) {
                s.node.disconnectAll()
                if (wifi.state.value.group.formed) withContext(Dispatchers.Main) { wifi.disconnect() }
            }
        }
    }

    private fun dialOwner(owner: InetAddress) {
        scope.launch {
            val node = _stack.value?.node ?: return@launch
            repeat(6) { attempt ->
                if (node.connect(owner.hostAddress!!, node.config.tcpPort).isSuccess) return@launch
                delay(1_000L + attempt * 500)
            }
            log.w("app", "could not reach the group owner's link service at ${owner.hostAddress}")
        }
    }

    /** Sends an SOS, first waiting up to 8 s for a GPS fix so the alert can include coordinates. */
    suspend fun sendSos(note: String): Int {
        val s = _stack.value ?: return 0
        sosPending.value = true
        try {
            kotlinx.coroutines.withTimeoutOrNull(8_000) {
                s.location.myFix.first { it != null && System.currentTimeMillis() - it.at < 120_000 }
            }
            return s.location.sendSos(note)
        } finally {
            sosPending.value = false
        }
    }

    /** Starts Wi-Fi Aware once the nearby-devices permission is granted (also called after a scan grants it). */
    fun startAwareIfAllowed() {
        if (_stack.value == null || !settings.value.useWifiAware || awareConflict.value) return
        val perm = if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION
        if (hasPermission(perm)) aware.start()
    }

    private fun dialAware(host: String) {
        scope.launch {
            val node = _stack.value?.node ?: return@launch
            repeat(4) { attempt ->
                if (node.connect(host, node.config.tcpPort).isSuccess) return@launch
                delay(800L + attempt * 700)
            }
            log.w("app", "could not open an SRCS link over Wi-Fi Aware")
        }
    }

    /** Send a message, queueing it locally while offline. */
    suspend fun sendMessage(to: DeviceId, body: String) {
        val s = _stack.value
        if (s != null) {
            s.messaging.send(to, body)
            return
        }
        val kp = identityKey ?: return
        val me = LocalIdentity(kp, settings.value.displayName)
        val m = StoredMessage(DeviceId.random(), to, false, me.deviceId, me.displayName, to, true, body.trim().take(2000), System.currentTimeMillis(), status = MessageStatus.QUEUED)
        messageStore.insert(m)
    }

    suspend fun deleteConversation(id: DeviceId) = db.messages().deleteConversation(id.hex)

    // ------------------------------------------------------------------ battery-aware relaying

    @Volatile private var relayByBattery = true
    private var batteryReceiver: BroadcastReceiver? = null

    private fun registerBattery() {
        if (batteryReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
                val ok = plugged || pct > settings.value.lowBatteryRelayCutoff
                if (ok != relayByBattery) {
                    relayByBattery = ok
                    if (!ok) log.i("battery", "battery at $pct%: relaying for others paused")
                    applyRelayPolicy()
                }
            }
        }
        ContextCompat.registerReceiver(app, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        batteryReceiver = r
    }

    private fun unregisterBattery() {
        batteryReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        batteryReceiver = null
    }

    private fun applyRelayPolicy() {
        _stack.value?.node?.setRelayPolicy(settings.value.relayEnabled, relayByBattery)
    }

    val relayPausedForBattery: Boolean get() = !relayByBattery

    fun hasPermission(p: String) = ContextCompat.checkSelfPermission(app, p) == PackageManager.PERMISSION_GRANTED

    internal fun mediaHooks(stackProvider: () -> LinkStack?) = object : MediaHooks {
        override fun onAudioSessionStarting(): Boolean {
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                log.w("audio", "microphone permission not granted")
                return false
            }
            val oneToOne = stackProvider()?.calls?.state?.value?.phase?.isLive == true
            audioRouter.begin(speakerDefault = !oneToOne)
            LinkService.setMicrophone(app, true)
            return true
        }

        override fun onAudioSessionStopped() {
            audioRouter.end()
            LinkService.setMicrophone(app, false)
        }
    }
}

/** Everything that exists only while online. Recreated on each "go online". */
class LinkStack(private val c: AppContainer, identity: LocalIdentity, engine: AndroidAudioEngine) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val node: LinkNode = LinkNode(identity, c.trustStore, NodeConfig(), c.log)
    val media: MediaCoordinator = MediaCoordinator(node, engine, scope, c.mediaHooks { this })
    val calls: CallManager = CallManager(node, media, scope)
    val groups: GroupManager = GroupManager(
        node, media, scope, isCallActive = { calls.state.value.phase.isLive }, maxMembers = { c.settings.value.maxGroupMembers },
        autoJoin = { c.settings.value.autoJoinWalkie },
    )
    val messaging = MessagingService(
        node, c.messageStore, groups, scope,
        MessagingConfig(retentionMs = { c.settings.value.retentionDays * 24L * 3_600_000L }),
    )
    val location = LocationService(node, groups, scope)
    val dj = DjService(node, groups, media, engine, scope)

    init {
        scope.launch {
            calls.finished.collect { s ->
                val peer = s.peer ?: return@collect
                val duration = s.activeSince?.let { System.currentTimeMillis() - it } ?: 0
                c.db.calls().insert(CallLogEntity(0, peer.hex, node.peerName(peer), s.outgoing, s.createdAt, duration, s.endReason?.name ?: EndReason.HANGUP_LOCAL.name))
                if (s.endReason == EndReason.MISSED) c.notifications.showMissedCall(node.peerName(peer))
            }
        }
        scope.launch {
            calls.state.collect { s ->
                if (s.phase == CallPhase.INCOMING_RINGING && s.peer != null) c.notifications.showIncomingCall(node.peerName(s.peer!!))
                else c.notifications.cancelIncomingCall()
            }
        }
        scope.launch {
            groups.group.collect { g -> if (g != null) c.db.groupNames().upsert(GroupNameEntity(g.groupId.hex, g.name, System.currentTimeMillis())) }
        }
        scope.launch {
            var seen = emptySet<DeviceId>()
            groups.invites.collect { list ->
                // Notify once per invitation; inside the app the Home screen shows a Join banner.
                list.filter { it.groupId !in seen }.forEach { inv ->
                    c.notifications.showGroupInvite(inv.fromName, inv.name, inv.mode == GroupMode.PTT)
                }
                seen = list.map { it.groupId }.toSet()
                if (list.isEmpty()) c.notifications.cancelGroupInvite()
            }
        }
        scope.launch {
            groups.events.collect { e ->
                if (e is GroupEvent.AutoJoined && !c.appVisible.value) {
                    c.notifications.showInfo("Walkie-talkie with ${e.hostName}", "You joined automatically. Open SRCS and hold the button to talk.")
                }
            }
        }
        scope.launch {
            node.incoming.collect { inc -> if (inc.message is AppMessage.LinkBye) c.onPeerBye(this@LinkStack, inc.from) }
        }
        scope.launch {
            messaging.received.collect { m ->
                if (c.openConversation.value != m.conversationId || !c.appVisible.value) c.notifications.showMessage(m)
            }
        }
        scope.launch {
            node.events.collect { e ->
                if (e is NodeEvent.PairingRequested && !c.appVisible.value) c.notifications.showPairing(e.link.remoteName)
            }
        }
        scope.launch {
            location.sosReceived.collect { a ->
                val me = location.myFix.value
                val where = a.fix?.let { f ->
                    if (me != null) Geo.formatDistance(Geo.distanceM(me.lat, me.lon, f.lat, f.lon)) + " " + Geo.compass(Geo.bearingDeg(me.lat, me.lon, f.lat, f.lon))
                    else "%.5f, %.5f".format(f.lat, f.lon)
                } ?: "no GPS position"
                c.notifications.showSos(a.name, a.note, where)
            }
        }
    }

    suspend fun start() = node.start()

    suspend fun stop() {
        location.stopSharing()
        dj.stop()
        if (calls.state.value.phase.isLive) calls.hangup()
        groups.leave()
        delay(300) // let the goodbye messages go out
        node.stop()
        scope.cancel()
    }
}
