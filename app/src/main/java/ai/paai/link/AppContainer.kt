package ai.paai.link

import ai.paai.link.audio.AndroidAudioEngine
import ai.paai.link.audio.AudioRouter
import ai.paai.link.core.call.CallManager
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.call.EndReason
import ai.paai.link.core.crypto.KeyPair
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.group.GroupManager
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
    )

    /** Wi-Fi Aware: direct phone-to-phone links without a group owner (Android 10+, hardware dependent). */
    val aware = WifiAwareController(
        app, log,
        tcpPort = { _stack.value?.node?.tcpPort?.takeIf { it > 0 } ?: 47700 },
        onPeerAddress = { host -> dialAware(host) },
    )

    init {
        scope.launch(Dispatchers.IO) {
            val (kp, created) = KeystoreIdentity.loadOrCreate(app)
            identityKey = kp
            _fingerprint.value = LocalIdentity(kp, "").fingerprint
            if (created) log.i("identity", "new device identity created")
        }
        scope.launch {
            settings.map { it.relayEnabled }.distinctUntilChanged().collect { applyRelayPolicy() }
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

    /** Starts Wi-Fi Aware once the nearby-devices permission is granted (also called after a scan grants it). */
    fun startAwareIfAllowed() {
        if (_stack.value == null) return
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
    val groups: GroupManager = GroupManager(node, media, scope, isCallActive = { calls.state.value.phase.isLive }, maxMembers = { c.settings.value.maxGroupMembers })
    val messaging = MessagingService(
        node, c.messageStore, groups, scope,
        MessagingConfig(retentionMs = { c.settings.value.retentionDays * 24L * 3_600_000L }),
    )

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
            groups.invites.collect { list -> list.lastOrNull()?.let { if (!c.appVisible.value) c.notifications.showGroupInvite(it.fromName, it.name) } }
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
    }

    suspend fun start() = node.start()

    suspend fun stop() {
        if (calls.state.value.phase.isLive) calls.hangup()
        groups.leave()
        delay(300) // let the goodbye messages go out
        node.stop()
        scope.cancel()
    }
}
