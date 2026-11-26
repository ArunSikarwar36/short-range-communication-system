package ai.paai.link.service

import ai.paai.link.LinkApplication
import ai.paai.link.core.call.CallPhase
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the link node alive while the app is in the background.
 * Type connectedDevice while online; microphone is added only while a voice session is active,
 * as Android requires for background microphone use.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var locationActive = false
    private var micActive = false
    private var observer: Job? = null
    private var statusText = "Online"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MIC_ON -> micActive = true
            ACTION_MIC_OFF -> micActive = false
            ACTION_LOC_ON -> locationActive = true
            ACTION_LOC_OFF -> locationActive = false
        }
        promote()
        if (observer == null) observer = observe()
        return START_NOT_STICKY
    }

    private fun promote() {
        val container = (application as LinkApplication).container
        var types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        if (micActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        // Location type only while the user shares their location (keeps GPS updates in the background).
        if (locationActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && container.gps.hasPermission()) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        try {
            ServiceCompat.startForeground(this, Notifications.STATUS_ID, container.notifications.status(statusText, micActive), types)
        } catch (e: Exception) {
            // Android refuses some foreground-service starts from the background; the call still works
            // while the app is visible, and the diagnostics log explains the restriction.
            container.log.w("service", "foreground service not allowed now: ${e.javaClass.simpleName}")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe(): Job = scope.launch {
        val c = (application as LinkApplication).container
        launch {
            c.stack.flatMapLatest { s ->
                if (s == null) flowOf("Offline")
                else combine(s.node.linkInfos, s.calls.state, s.groups.group) { links, call, group ->
                    when {
                        call.phase == CallPhase.ACTIVE -> "In call with ${s.node.peerName(call.peer!!)}"
                        call.phase.isLive -> "Call: ${call.phase.name.lowercase().replace('_', ' ')}"
                        group != null -> "Group '${group.name}' · ${group.members.size} members"
                        else -> "Online · ${links.count { it.trusted }} linked"
                    }
                }
            }.collect { text ->
                statusText = text
                c.notifications.updateStatus(text, micActive)
            }
        }
        launch {
            while (isActive) { delay(30_000); c.notifications.updateStatus(statusText, micActive) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "ai.paai.link.START"
        private const val ACTION_STOP = "ai.paai.link.STOP"
        private const val ACTION_MIC_ON = "ai.paai.link.MIC_ON"
        private const val ACTION_MIC_OFF = "ai.paai.link.MIC_OFF"
        private const val ACTION_LOC_ON = "ai.paai.link.LOC_ON"
        private const val ACTION_LOC_OFF = "ai.paai.link.LOC_OFF"

        fun start(context: Context) = send(context, ACTION_START)
        fun stop(context: Context) = runCatching { context.startService(Intent(context, LinkService::class.java).setAction(ACTION_STOP)) }
        fun setMicrophone(context: Context, on: Boolean) = send(context, if (on) ACTION_MIC_ON else ACTION_MIC_OFF)
        fun setLocationSharing(context: Context, on: Boolean) = send(context, if (on) ACTION_LOC_ON else ACTION_LOC_OFF)

        private fun send(context: Context, action: String) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, LinkService::class.java).setAction(action))
            } catch (e: Exception) {
                (context.applicationContext as LinkApplication).container.log.w("service", "could not start service: ${e.javaClass.simpleName}")
            }
        }
    }
}
