package ai.paai.link.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioRoute(val label: String) { EARPIECE("Phone"), SPEAKER("Speaker"), BLUETOOTH("Bluetooth"), WIRED("Headset") }

data class RouteState(val active: Boolean = false, val current: AudioRoute? = null, val available: List<AudioRoute> = emptyList())

/**
 * Audio focus, communication mode and output routing for voice sessions. Uses the
 * communication-device API on Android 12+, and the legacy speakerphone / SCO calls below that.
 * Also holds a proximity wake lock while the earpiece is used, so the screen turns off at the ear.
 */
@Suppress("DEPRECATION")
class AudioRouter(private val context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val pm = context.getSystemService(PowerManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var focus: AudioFocusRequest? = null
    private var proximity: PowerManager.WakeLock? = null
    private var preferSpeaker = false
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = onDevicesChanged(added.any { it.isHeadsetLike() })
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = onDevicesChanged(false)
    }

    /** Start of a voice session. [speakerDefault] is true for group / push-to-talk sessions. */
    @Synchronized
    fun begin(speakerDefault: Boolean) {
        preferSpeaker = speakerDefault
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(req)
        focus = req
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.registerAudioDeviceCallback(deviceCallback, main)
        _state.value = RouteState(active = true)
        select(defaultRoute())
    }

    @Synchronized
    fun end() {
        if (!_state.value.active) return
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            am.isSpeakerphoneOn = false
            if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
        }
        am.mode = AudioManager.MODE_NORMAL
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
        releaseProximity()
        _state.value = RouteState()
    }

    @Synchronized
    fun select(route: AudioRoute) {
        if (!_state.value.active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dev = am.availableCommunicationDevices.firstOrNull { it.route() == route }
            if (dev != null) am.setCommunicationDevice(dev)
        } else {
            when (route) {
                AudioRoute.SPEAKER -> { stopSco(); am.isSpeakerphoneOn = true }
                AudioRoute.BLUETOOTH -> { am.isSpeakerphoneOn = false; am.startBluetoothSco(); am.isBluetoothScoOn = true }
                else -> { stopSco(); am.isSpeakerphoneOn = false }
            }
        }
        if (route == AudioRoute.EARPIECE) acquireProximity() else releaseProximity()
        _state.value = RouteState(true, route, available())
    }

    private fun stopSco() {
        if (am.isBluetoothScoOn) { am.stopBluetoothSco(); am.isBluetoothScoOn = false }
    }

    private fun onDevicesChanged(headsetAdded: Boolean) {
        if (!_state.value.active) return
        val avail = available()
        val cur = _state.value.current
        // A newly connected headset takes over; a removed one falls back to the default route.
        val next = when {
            headsetAdded -> avail.firstOrNull { it == AudioRoute.BLUETOOTH || it == AudioRoute.WIRED } ?: cur
            cur != null && cur !in avail -> defaultRoute()
            else -> cur
        }
        if (next != null && next != cur) select(next) else _state.value = _state.value.copy(available = avail)
    }

    private fun defaultRoute(): AudioRoute {
        val avail = available()
        return avail.firstOrNull { it == AudioRoute.BLUETOOTH }
            ?: avail.firstOrNull { it == AudioRoute.WIRED }
            ?: if (preferSpeaker || AudioRoute.EARPIECE !in avail) AudioRoute.SPEAKER else AudioRoute.EARPIECE
    }

    private fun available(): List<AudioRoute> {
        val routes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices.mapNotNull { it.route() }
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { it.route() }
        }
        return routes.distinct().sortedBy { it.ordinal }
    }

    private fun AudioDeviceInfo.route(): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoute.BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoute.WIRED
        else -> null
    }

    private fun AudioDeviceInfo.isHeadsetLike() = route() == AudioRoute.BLUETOOTH || route() == AudioRoute.WIRED

    private fun acquireProximity() {
        if (proximity != null || !pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "paai:proximity").apply {
            setReferenceCounted(false)
            acquire(4 * 3_600_000L)
        }
    }

    private fun releaseProximity() {
        proximity?.let { runCatching { it.release() } }
        proximity = null
    }
}
