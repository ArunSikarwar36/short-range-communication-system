package ai.paai.link.service

import ai.paai.link.LinkApplication
import ai.paai.link.MainActivity
import ai.paai.link.R
import ai.paai.link.core.messaging.StoredMessage
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch

class Notifications(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)
    private val sys = context.getSystemService(NotificationManager::class.java)

    init {
        val ringtone = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        sys.createNotificationChannels(
            listOf(
                NotificationChannel(CH_STATUS, "Connection status", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while SRCS is online or in a call"
                },
                NotificationChannel(CH_CALLS, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(ringtone, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 800, 600, 800)
                },
                NotificationChannel(CH_MESSAGES, "Messages", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_EVENTS, "Pairing, groups and missed calls", NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_SOS, "SOS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Emergency alerts from paired phones and your group"
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 1000, 400, 1000, 400, 1000)
                },
            ),
        )
    }

    private fun openApp(action: String? = null, req: Int = 0): PendingIntent =
        PendingIntent.getActivity(
            context, req,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun status(text: String, mic: Boolean) = NotificationCompat.Builder(context, CH_STATUS)
        .setSmallIcon(R.drawable.ic_stat_link)
        .setContentTitle("SRCS")
        .setContentText(text)
        .setSubText(if (mic) "Microphone in use" else null)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(if (mic) NotificationCompat.CATEGORY_CALL else NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(openApp())
        .addAction(0, "Go offline", PendingIntent.getBroadcast(context, 10, Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.GO_OFFLINE), PendingIntent.FLAG_IMMUTABLE))
        .build()

    fun updateStatus(text: String, mic: Boolean) = post(STATUS_ID, status(text, mic))

    fun showIncomingCall(name: String) {
        val decline = PendingIntent.getBroadcast(
            context, 1, Intent(context, CallActionReceiver::class.java).setAction(CallActionReceiver.DECLINE), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Incoming call")
            .setContentText(name)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(openApp(MainActivity.ACTION_SHOW_CALL, 2))
            .addAction(0, "Decline", decline)
            .addAction(0, "Answer", openApp(MainActivity.ACTION_ANSWER, 3))
        val canFullScreen = Build.VERSION.SDK_INT < 34 || sys.canUseFullScreenIntent()
        if (canFullScreen) b.setFullScreenIntent(openApp(MainActivity.ACTION_SHOW_CALL, 4), true)
        post(INCOMING_ID, b.build())
    }

    fun cancelIncomingCall() = nm.cancel(INCOMING_ID)

    fun showMissedCall(name: String) = post(
        MISSED_ID,
        NotificationCompat.Builder(context, CH_EVENTS).setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Missed call").setContentText(name).setAutoCancel(true).setContentIntent(openApp()).build(),
    )

    fun showMessage(m: StoredMessage) = post(
        MESSAGE_ID_BASE + (m.conversationId.hashCode() and 0xffff),
        NotificationCompat.Builder(context, CH_MESSAGES).setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle(m.authorName)
            .setContentText(m.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.body))
            .setAutoCancel(true)
            .setContentIntent(openApp("${MainActivity.ACTION_OPEN_CHAT}:${m.conversationId.hex}", 5))
            .build(),
    )

    fun showGroupInvite(from: String, group: String) = post(
        GROUP_ID,
        NotificationCompat.Builder(context, CH_EVENTS).setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Group invitation").setContentText("$from invited you to '$group'").setAutoCancel(true)
            .setContentIntent(openApp()).build(),
    )

    fun showPairing(name: String) = post(
        PAIR_ID,
        NotificationCompat.Builder(context, CH_EVENTS).setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Pairing request").setContentText("Open the app to compare the code with $name").setAutoCancel(true)
            .setContentIntent(openApp()).build(),
    )

    fun showSos(name: String, note: String, where: String) = post(
        SOS_ID,
        NotificationCompat.Builder(context, CH_SOS).setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("SOS from $name")
            .setContentText(listOf(note, where).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Needs help" })
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true).setContentIntent(openApp()).build(),
    )

    fun cancelAll() = nm.cancelAll()

    @SuppressLint("MissingPermission")
    private fun post(id: Int, n: android.app.Notification) {
        if (nm.areNotificationsEnabled()) nm.notify(id, n)
    }

    companion object {
        const val CH_STATUS = "status"
        const val CH_CALLS = "calls"
        const val CH_MESSAGES = "messages"
        const val CH_EVENTS = "events"
        const val CH_SOS = "sos"
        const val STATUS_ID = 1
        const val INCOMING_ID = 2
        const val MISSED_ID = 3
        const val GROUP_ID = 4
        const val PAIR_ID = 5
        const val SOS_ID = 6
        const val MESSAGE_ID_BASE = 1000
    }
}

/** Notification actions that must work without opening the app. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val c = (context.applicationContext as LinkApplication).container
        when (intent.action) {
            DECLINE -> c.stack.value?.calls?.reject()
            HANGUP -> c.stack.value?.calls?.hangup()
            GO_OFFLINE -> {
                val pending = goAsync()
                c.scope.launch {
                    try { c.goOffline() } finally { pending.finish() }
                }
            }
        }
    }

    companion object {
        const val DECLINE = "ai.paai.link.DECLINE"
        const val HANGUP = "ai.paai.link.HANGUP"
        const val GO_OFFLINE = "ai.paai.link.GO_OFFLINE"
    }
}
