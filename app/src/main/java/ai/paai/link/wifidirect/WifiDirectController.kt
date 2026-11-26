package ai.paai.link.wifidirect

import ai.paai.link.core.node.LogBuffer
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.InetAddress

data class P2pPeer(val name: String, val address: String, val status: Int) {
    val statusLabel: String get() = when (status) {
        WifiP2pDevice.AVAILABLE -> "Nearby"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}

data class P2pGroupInfo(
    val formed: Boolean = false,
    val isOwner: Boolean = false,
    val ownerAddress: String? = null,
    val networkName: String? = null,
    val clientCount: Int = 0,
)

data class WifiDirectState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val registered: Boolean = false,
    val discovering: Boolean = false,
    val peers: List<P2pPeer> = emptyList(),
    val group: P2pGroupInfo = P2pGroupInfo(),
    val thisDeviceName: String? = null,
    val lastError: String? = null,
)

/**
 * Wraps WifiP2pManager. The Wi-Fi Direct group only provides the IP link; device identity,
 * authentication and the application handshake are done by [ai.paai.link.core.node.LinkNode].
 *
 * Group-owner / client roles: the owner is reachable at a fixed address (192.168.49.1 on Android)
 * and accepts TCP links; clients dial the owner ([onGroupFormed] reports the role and address).
 */
@SuppressLint("MissingPermission") // Every entry point is reached only after the UI obtained the discovery permission.
class WifiDirectController(
    private val context: Context,
    private val log: LogBuffer,
    private val onGroupFormed: (ownerAddress: InetAddress, isOwner: Boolean) -> Unit,
    private val onGroupRemoved: () -> Unit,
) {
    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val supported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT) && manager != null
    private var channel: WifiP2pManager.Channel? = null
    private val _state = MutableStateFlow(WifiDirectState(supported = supported))
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()
    private var lastFormed = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    _state.update { it.copy(enabled = enabled, lastError = if (enabled) null else "Wi-Fi is off. Turn on Wi-Fi to find nearby devices.") }
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnection()
                WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                    val started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                    _state.update { it.copy(discovering = started) }
                }
                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val dev: WifiP2pDevice? = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    _state.update { it.copy(thisDeviceName = dev?.deviceName) }
                }
            }
        }
    }

    fun register() {
        if (!supported || _state.value.registered) return
        channel = manager!!.initialize(context, Looper.getMainLooper()) {
            log.w(TAG, "Wi-Fi Direct channel lost")
            _state.update { it.copy(registered = false, discovering = false) }
            channel = null
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        _state.update { it.copy(registered = true) }
        requestConnection()
    }

    fun unregister() {
        if (!_state.value.registered) return
        runCatching { context.unregisterReceiver(receiver) }
        stopDiscovery()
        if (Build.VERSION.SDK_INT >= 27) runCatching { channel?.close() }
        channel = null
        _state.update { WifiDirectState(supported = supported) }
    }

    fun discover() {
        val ch = channel ?: return fail("Wi-Fi Direct is not ready")
        manager!!.discoverPeers(ch, listener("scan") { _state.update { it.copy(discovering = true, lastError = null) } })
    }

    fun stopDiscovery() {
        val ch = channel ?: return
        manager!!.stopPeerDiscovery(ch, listener("stop scan") { _state.update { it.copy(discovering = false) } })
    }

    /** Ask [peer] to form a group. The other phone shows a system prompt to accept the invitation. */
    fun connect(peer: P2pPeer) {
        val ch = channel ?: return fail("Wi-Fi Direct is not ready")
        val config = WifiP2pConfig().apply {
            deviceAddress = peer.address
            @Suppress("DEPRECATION")
            wps.setup = WpsInfo.PBC
        }
        log.i(TAG, "requesting Wi-Fi Direct connection to ${peer.name}")
        manager!!.connect(ch, config, listener("connect to ${peer.name}"))
    }

    /** Become group owner so several phones can join (needed for group calls and relaying). */
    fun createGroup() {
        val ch = channel ?: return fail("Wi-Fi Direct is not ready")
        manager!!.createGroup(ch, listener("create group") { log.i(TAG, "hosting a Wi-Fi Direct group") })
    }

    fun disconnect() {
        val ch = channel ?: return
        manager!!.cancelConnect(ch, null)
        manager.removeGroup(ch, listener("leave group") { log.i(TAG, "left Wi-Fi Direct group") })
    }

    private fun requestPeers() {
        val ch = channel ?: return
        manager!!.requestPeers(ch) { list ->
            val peers = list.deviceList.map { P2pPeer(it.deviceName.ifBlank { "Unnamed device" }, it.deviceAddress, it.status) }
                .sortedBy { it.name.lowercase() }
            _state.update { it.copy(peers = peers) }
        }
    }

    private fun requestConnection() {
        val ch = channel ?: return
        manager!!.requestConnectionInfo(ch) { info ->
            if (info == null || !info.groupFormed) {
                _state.update { it.copy(group = P2pGroupInfo()) }
                if (lastFormed) {
                    lastFormed = false
                    log.i(TAG, "Wi-Fi Direct group removed")
                    onGroupRemoved()
                }
                return@requestConnectionInfo
            }
            val owner = info.groupOwnerAddress
            _state.update { it.copy(group = it.group.copy(formed = true, isOwner = info.isGroupOwner, ownerAddress = owner?.hostAddress)) }
            manager.requestGroupInfo(ch) { g ->
                _state.update { it.copy(group = it.group.copy(networkName = g?.networkName, clientCount = g?.clientList?.size ?: 0)) }
            }
            if (!lastFormed && owner != null) {
                lastFormed = true
                log.i(TAG, "group formed: ${if (info.isGroupOwner) "this phone is group owner" else "client, owner at ${owner.hostAddress}"}")
                onGroupFormed(owner, info.isGroupOwner)
            }
        }
    }

    private fun listener(what: String, onOk: () -> Unit = {}) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = onOk()
        override fun onFailure(reason: Int) = fail("$what failed: ${reasonText(reason)}")
    }

    private fun fail(msg: String) {
        log.w(TAG, msg)
        _state.update { it.copy(lastError = msg) }
    }

    fun clearError() = _state.update { it.copy(lastError = null) }

    companion object {
        private const val TAG = "wifi-direct"

        fun reasonText(reason: Int) = when (reason) {
            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy; try again in a moment"
            WifiP2pManager.ERROR -> "internal error (is Wi-Fi on?)"
            WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
            else -> "error $reason"
        }
    }
}
