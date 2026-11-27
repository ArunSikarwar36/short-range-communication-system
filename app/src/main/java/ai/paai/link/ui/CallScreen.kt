package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.audio.AudioRoute
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.media.MediaStats
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@Composable
fun CallScreen(vm: LinkViewModel, gate: PermissionGate) {
    val call by vm.call.collectAsStateWithLifecycle()
    val session by vm.callSession.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val name = call.peer?.let { stack?.node?.peerName(it) } ?: "Unknown"
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stats by remember { mutableStateOf(MediaStats()) }

    LaunchedEffect(session) {
        while (true) {
            now = System.currentTimeMillis()
            session?.let { it.refresh(); stats = it.stats.value }
            delay(500)
        }
    }
    BackHandler(enabled = call.phase.isLive) { /* keep the call screen until the call ends */ }

    Box(
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.radialGradient(
                listOf(LinkColors.Accent.copy(alpha = if (call.phase == CallPhase.ACTIVE) 0.30f else 0.16f), LinkColors.Background),
                radius = 1400f,
            ))
            .systemBarsPadding(),
    ) {
        Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(72.dp))
            AvatarBubble(name, sizeDp = 128, ring = if (call.phase == CallPhase.ACTIVE) LinkColors.Good else Color.White)
            Spacer(Modifier.height(20.dp))
            Text(name, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            val status = when (call.phase) {
                CallPhase.OUTGOING_INVITE -> "Calling…"
                CallPhase.REMOTE_RINGING -> "Ringing…"
                CallPhase.INCOMING_RINGING -> "Incoming call"
                CallPhase.CONNECTING -> "Connecting audio…"
                CallPhase.ACTIVE -> formatDuration(now - (call.activeSince ?: now))
                CallPhase.RECONNECTING -> "Reconnecting…"
                CallPhase.ENDING -> "Ending…"
                CallPhase.ENDED, CallPhase.FAILED -> call.endReason?.label ?: "Call ended"
                CallPhase.IDLE -> ""
            }
            Text(
                status,
                color = if (call.phase == CallPhase.RECONNECTING || call.phase == CallPhase.FAILED) LinkColors.Warn else LinkColors.Muted,
                style = MaterialTheme.typography.titleMedium,
            )
            if (call.phase == CallPhase.ACTIVE || call.phase == CallPhase.RECONNECTING) {
                Spacer(Modifier.height(12.dp))
                QualityBars(stats)
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Color(0xFF0B1118), androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 24.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (call.phase.hasMedia && route.available.size > 1) {
                Text("Audio: ${route.current?.label ?: "-"}", color = LinkColors.Muted, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                when (call.phase) {
                    CallPhase.INCOMING_RINGING -> {
                        RoundButton(R.drawable.ic_call_end, "Decline", LinkColors.Bad) { vm.reject() }
                        RoundButton(null, "Answer", LinkColors.Good) {
                            gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.accept() }
                        }
                    }
                    CallPhase.ENDED, CallPhase.FAILED, CallPhase.ENDING -> {}
                    else -> {
                        if (call.phase.hasMedia) {
                            RoundButton(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, if (muted) "Mic Off" else "Mic On",
                                if (muted) LinkColors.Warn else Color(0xFF2A3644)) { vm.setMuted(!muted) }
                            RoundButton(R.drawable.ic_speaker, "Audio", Color.White, iconTint = Color(0xFF0A0F16)) {
                                val all = route.available
                                if (all.isNotEmpty()) vm.selectRoute(all[(all.indexOf(route.current) + 1) % all.size])
                            }
                        }
                        RoundButton(R.drawable.ic_call_end, "End", LinkColors.Bad) { vm.hangup() }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun RoundButton(icon: Int?, label: String, color: Color, iconTint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(72.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = iconTint),
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(32.dp))
            else Icon(Icons.Default.Call, contentDescription = label, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = LinkColors.Muted)
    }
}

/** Connection quality from measured loss and jitter, with the numbers spelled out. */
@Composable
fun QualityBars(stats: MediaStats) {
    val q = stats.quality
    val label = when (q) { 0 -> "Waiting for audio"; 1 -> "Poor connection"; 2 -> "Fair connection"; else -> "Good connection" }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics { contentDescription = label }) {
        for (i in 1..3) {
            Box(
                Modifier
                    .width(6.dp)
                    .height((6 + i * 6).dp)
                    .background(if (q >= i) (if (q == 1) LinkColors.Bad else if (q == 2) LinkColors.Warn else LinkColors.Good) else LinkColors.SurfaceHigh, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(3.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "$label · loss ${"%.1f".format(stats.lossPercent)}% · jitter ${stats.jitterMs} ms · ${stats.bitrate / 1000} kbps",
            style = MaterialTheme.typography.bodySmall, color = LinkColors.Muted,
        )
    }
}
