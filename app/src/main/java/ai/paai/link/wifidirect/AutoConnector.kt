package ai.paai.link.wifidirect

import ai.paai.link.core.node.LogBuffer
import android.content.Context
import android.net.wifi.p2p.WifiP2pDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Reconnects by itself to phones this phone was connected to before, so nobody has to tap Scan
 * and Connect again. While online and not in a Wi-Fi Direct group it keeps a quiet scan running;
 * when a remembered phone shows up, one of the two phones (chosen by name so they do not both
 * dial at once) asks it to connect. Android remembers the earlier group, so usually no prompt
 * appears.
 *
 * After either phone taps Disconnect it stops dialling (but stays findable) until the user scans
 * or connects again.
 */
class AutoConnector(
    context: Context,
    private val log: LogBuffer,
    private val scope: CoroutineScope,
    private val wifi: WifiDirectController,
    private val enabled: () -> Boolean,
    private val canScan: () -> Boolean,
) {
    private val prefs = context.getSharedPreferences("p2p_known", Context.MODE_PRIVATE)
    private val _status = MutableStateFlow<String?>(null)
    /** "Looking for your phones…" / "Reconnecting to …", or null when idle. */
    val status: StateFlow<String?> = _status.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private var job: Job? = null

    val knowsAnyPhone: Boolean get() = names().isNotEmpty() || addresses().isNotEmpty()

    fun start() {
        if (job?.isActive == true) return
        _paused.value = false
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        _status.value = null
    }

    /** Stop reconnecting by itself (the user or the other phone disconnected on purpose). */
    fun pause() {
        _paused.value = true
        _status.value = null
    }

    /** The user scanned or connected by hand: reconnecting is welcome again. */
    fun resume() {
        _paused.value = false
    }

    /** Remember the phones in the current group (called once an SRCS link with a paired phone is up). */
    fun remember(members: List<P2pPeer>) {
        val n = names()
        val a = addresses()
        val newNames = members.map { it.name }.filter { it.isNotBlank() && it !in n }
        val newAddrs = members.map { it.address }.filter { it.isNotBlank() && it != "02:00:00:00:00:00" && it !in a }
        if (newNames.isEmpty() && newAddrs.isEmpty()) return
        prefs.edit().putStringSet(K_NAMES, n + newNames).putStringSet(K_ADDRS, a + newAddrs).apply()
        log.i(TAG, "remembered ${newNames.size + newAddrs.size} Wi-Fi Direct device detail(s) for reconnecting")
    }

    fun forgetAll() = prefs.edit().clear().apply()

    private fun names(): Set<String> = prefs.getStringSet(K_NAMES, emptySet()).orEmpty()
    private fun addresses(): Set<String> = prefs.getStringSet(K_ADDRS, emptySet()).orEmpty()
    private fun isKnown(p: P2pPeer) = p.name in names() || p.address in addresses()

    private suspend fun loop() {
        var lastScan = 0L
        var lastAttempt = 0L
        val firstSeen = HashMap<String, Long>()
        while (scope.isActive) {
            delay(3_000)
            val st = wifi.state.value
            val now = System.currentTimeMillis()
            if (!enabled() || !st.registered || !st.enabled || !canScan() || !knowsAnyPhone) {
                _status.value = null
                continue
            }
            if (st.group.formed) {
                _status.value = null
                firstSeen.clear()
                continue
            }
            // Discovery stops by itself after a while and after connections; keep it going quietly.
            // Even while paused: a phone is only findable while it scans, so the other phone can
            // still reach this one when its user taps Scan.
            if (!st.discovering && now - lastScan > (if (_paused.value) 30_000 else 12_000)) {
                lastScan = now
                wifi.discover(quiet = true)
            }
            if (_paused.value) {
                _status.value = null
                continue
            }
            val match = st.peers.firstOrNull { isKnown(it) && it.status == WifiP2pDevice.AVAILABLE }
            if (match == null) {
                _status.value = "Looking for your phones…"
                continue
            }
            _status.value = "Reconnecting to ${match.name}…"
            if (st.thisDeviceName == null) wifi.requestOwnName()
            val seen = firstSeen.getOrPut(match.address) { now }
            // Only one side dials: the one whose name sorts first. The other takes over after 20 s
            // in case the first one cannot (e.g. its app is closed).
            val iDial = (st.thisDeviceName ?: "") < match.name || now - seen > 20_000
            if (iDial && now - lastAttempt > 25_000) {
                lastAttempt = now
                log.i(TAG, "reconnecting to ${match.name}")
                wifi.connect(match)
            }
        }
    }

    private companion object {
        const val TAG = "auto-connect"
        const val K_NAMES = "names"
        const val K_ADDRS = "addresses"
    }
}
