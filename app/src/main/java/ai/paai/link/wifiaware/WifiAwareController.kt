package ai.paai.link.wifiaware

import ai.paai.link.core.node.LogBuffer
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.AttachCallback
import android.net.wifi.aware.DiscoverySessionCallback
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishConfig
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.SubscribeConfig
import android.net.wifi.aware.SubscribeDiscoverySession
import android.net.wifi.aware.WifiAwareManager
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.net.wifi.aware.WifiAwareSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.Inet6Address
import java.security.SecureRandom

/** A phone running SRCS seen over Wi-Fi Aware (not yet necessarily linked). */
data class AwarePeer(val key: String, val linked: Boolean, val seenAt: Long)

data class WifiAwareState(
    /** Hardware and Android version support Wi-Fi Aware data paths (Android 10+). */
    val supported: Boolean = false,
    /** Wi-Fi Aware is currently usable (Wi-Fi on, not blocked by hotspot / location settings). */
    val available: Boolean = false,
    val running: Boolean = false,
    val peers: List<AwarePeer> = emptyList(),
    val dataPaths: Int = 0,
    val maxDataPaths: Int = 0,
    val lastError: String? = null,
)

/**
 * Wi-Fi Aware (NAN) link layer. Unlike Wi-Fi Direct, a phone can keep several Aware data paths at
 * once and no phone has to "host", so phones can link to each other directly.
 *
 * Every phone both publishes and subscribes to the "srcs" service. The service info is only a
 * random per-session nonce (no name or device ID is broadcast). When two phones discover each
 * other, the one with the lower nonce asks for a data path; the other answers. Over the data path
 * (IPv6 link-local) the usual TCP link + Noise XX handshake and pairing code run, so Wi-Fi Aware
 * provides reachability only, never trust.
 */
@SuppressLint("MissingPermission") // started only after the UI obtained the nearby-devices permission
class WifiAwareController(
    private val context: Context,
    private val log: LogBuffer,
    private val tcpPort: () -> Int,
    /** Called with the peer's IPv6 address (including scope) once a data path is up and we initiated it. */
    private val onPeerAddress: (host: String) -> Unit,
) {
    private val manager: WifiAwareManager? = context.getSystemService(WifiAwareManager::class.java)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val supported = Build.VERSION.SDK_INT >= 29 &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE) && manager != null

    private val _state = MutableStateFlow(WifiAwareState(supported = supported))
    val state: StateFlow<WifiAwareState> = _state.asStateFlow()

    private var session: WifiAwareSession? = null
    private var publish: PublishDiscoverySession? = null
    private var subscribe: SubscribeDiscoverySession? = null
    private val nonce = ByteArray(8).also { SecureRandom().nextBytes(it) }
    private var wanted = false

    private class Peer(val key: String, var handle: PeerHandle, var viaSubscribe: Boolean, var seenAt: Long, var callback: ConnectivityManager.NetworkCallback? = null, var linked: Boolean = false)
    private val peers = HashMap<String, Peer>()

    private val availabilityReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val available = manager?.isAvailable == true
            _state.update { it.copy(available = available) }
            if (available && wanted && session == null) attach()
            if (!available) teardown("Wi-Fi Aware became unavailable")
        }
    }

    fun start() {
        if (!supported || wanted) return
        wanted = true
        ContextCompat.registerReceiver(context, availabilityReceiver, IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        val available = manager!!.isAvailable
        _state.update { it.copy(available = available, maxDataPaths = if (Build.VERSION.SDK_INT >= 33) manager.characteristics?.numberOfSupportedDataPaths ?: 0 else 0) }
        if (available) attach() else log.i(TAG, "Wi-Fi Aware not available right now (Wi-Fi off or hotspot on?)")
    }

    fun stop() {
        if (!wanted) return
        wanted = false
        runCatching { context.unregisterReceiver(availabilityReceiver) }
        teardown(null)
    }

    private fun attach() {
        manager?.attach(object : AttachCallback() {
            override fun onAttached(s: WifiAwareSession) {
                if (!wanted) { s.close(); return }
                session = s
                _state.update { it.copy(running = true, lastError = null) }
                log.i(TAG, "Wi-Fi Aware attached")
                startDiscovery(s)
            }

            override fun onAttachFailed() = fail("Wi-Fi Aware attach failed")
        }, handler)
    }

    private fun startDiscovery(s: WifiAwareSession) {
        s.publish(PublishConfig.Builder().setServiceName(SERVICE).setServiceSpecificInfo(nonce).build(), object : DiscoverySessionCallback() {
            override fun onPublishStarted(p: PublishDiscoverySession) { publish = p }
            override fun onMessageReceived(peer: PeerHandle, message: ByteArray) {
                // The other phone (lower nonce) asks for a data path: answer as responder.
                val key = message.toHexKey()
                if (message.size != 8) return
                val p = remember(key, peer, viaSubscribe = false)
                if (Build.VERSION.SDK_INT >= 29) requestDataPath(p, initiator = false)
            }
            override fun onSessionTerminated() { publish = null }
        }, handler)

        s.subscribe(SubscribeConfig.Builder().setServiceName(SERVICE).build(), object : DiscoverySessionCallback() {
            override fun onSubscribeStarted(sub: SubscribeDiscoverySession) { subscribe = sub }
            override fun onServiceDiscovered(peer: PeerHandle, info: ByteArray?, filter: MutableList<ByteArray>?) {
                if (info == null || info.size != 8 || info.contentEquals(nonce)) return
                val key = info.toHexKey()
                val p = remember(key, peer, viaSubscribe = true)
                // Lower nonce initiates, so exactly one side asks for the data path.
                if (compare(nonce, info) < 0 && p.callback == null && Build.VERSION.SDK_INT >= 29) {
                    subscribe?.sendMessage(peer, MSG_CONNECT, nonce)
                    requestDataPath(p, initiator = true)
                }
            }
            override fun onSessionTerminated() { subscribe = null }
        }, handler)
    }

    private fun remember(key: String, handle: PeerHandle, viaSubscribe: Boolean): Peer {
        val now = System.currentTimeMillis()
        val p = peers.getOrPut(key) { Peer(key, handle, viaSubscribe, now) }
        if (p.callback == null) { p.handle = handle; p.viaSubscribe = viaSubscribe }
        p.seenAt = now
        publishPeers()
        return p
    }

    @RequiresApi(29)
    private fun requestDataPath(p: Peer, initiator: Boolean) {
        if (p.callback != null) return
        val spec = if (initiator) {
            val sub = subscribe ?: return
            WifiAwareNetworkSpecifier.Builder(sub, p.handle).setPskPassphrase(PASSPHRASE).build()
        } else {
            val pub = publish ?: return
            WifiAwareNetworkSpecifier.Builder(pub, p.handle).setPskPassphrase(PASSPHRASE).setPort(tcpPort()).setTransportProtocol(6).build()
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE).setNetworkSpecifier(spec).build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var dialled = false

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = caps.transportInfo as? WifiAwareNetworkInfo ?: return
                handler.post {
                    p.linked = true
                    publishPeers()
                    if (initiator && !dialled) {
                        val addr = info.peerIpv6Addr ?: return@post
                        dialled = true
                        val host = (addr as? Inet6Address)?.hostAddress ?: addr.hostAddress ?: return@post
                        log.i(TAG, "data path up; linking to $host")
                        onPeerAddress(host)
                    } else if (!initiator) {
                        log.i(TAG, "data path up (answering)")
                    }
                }
            }

            override fun onLost(network: Network) = handler.post { dropPath(p, "data path lost") }.let {}
            override fun onUnavailable() = handler.post { dropPath(p, "data path unavailable") }.let {}
        }
        p.callback = cb
        runCatching { connectivity.requestNetwork(request, cb, handler, 15_000) }
            .onFailure { p.callback = null; fail("data path request failed: ${it.message}") }
        publishPeers()
    }

    private fun dropPath(p: Peer, why: String) {
        p.callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        p.callback = null
        p.linked = false
        log.i(TAG, why)
        publishPeers()
    }

    private fun publishPeers() {
        val cutoff = System.currentTimeMillis() - PEER_TTL_MS
        peers.values.removeAll { it.callback == null && it.seenAt < cutoff }
        _state.update { s ->
            s.copy(
                peers = peers.values.map { AwarePeer(it.key, it.linked, it.seenAt) }.sortedBy { it.key },
                dataPaths = peers.values.count { it.linked },
            )
        }
    }

    private fun teardown(reason: String?) {
        peers.values.forEach { p -> p.callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } } }
        peers.clear()
        runCatching { publish?.close() }
        runCatching { subscribe?.close() }
        runCatching { session?.close() }
        publish = null; subscribe = null; session = null
        _state.update { it.copy(running = false, peers = emptyList(), dataPaths = 0, lastError = reason ?: it.lastError) }
        reason?.let { log.i(TAG, it) }
    }

    private fun fail(msg: String) {
        log.w(TAG, msg)
        _state.update { it.copy(lastError = msg) }
    }

    private fun ByteArray.toHexKey() = joinToString("") { "%02x".format(it) }

    private fun compare(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return 0
    }

    companion object {
        private const val TAG = "wifi-aware"
        const val SERVICE = "srcs"
        private const val MSG_CONNECT = 1
        /** Encrypts the Aware data path itself; real authentication is the Noise handshake on top. */
        private const val PASSPHRASE = "srcs-wifi-aware-link-v1"
        private const val PEER_TTL_MS = 60_000L
    }
}
