package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.audio.AudioRoute
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.media.MediaStats
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.filled.Lock
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
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

    val live = call.phase == CallPhase.ACTIVE || call.phase == CallPhase.RECONNECTING
    val pulse = rememberInfiniteTransition(label = "ring")
    val p by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF3B1A5C), Color(0xFF160D26), Color(0xFF07070C)), center = Offset(540f, 800f), radius = 1500f))
            .systemBarsPadding(),
    ) {
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Lock, null, tint = LinkColors.Good, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("End-to-end encrypted", color = Ui.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(300.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    for (k in 0..1) {
                        val q = (p + k * 0.5f) % 1f
                        drawCircle(Ui.Purple.copy(alpha = (1 - q) * (if (call.phase == CallPhase.ENDED || call.phase == CallPhase.FAILED) 0f else 0.6f)), r * (0.5f + 0.5f * q), style = Stroke(2.dp.toPx()))
                    }
                }
                Box(
                    Modifier.size(150.dp).shadow(40.dp, CircleShape, ambientColor = Ui.Purple, spotColor = Ui.Purple)
                        .background(Brush.linearGradient(listOf(Ui.Purple, Ui.Pink)), CircleShape).padding(4.dp),
                ) { AvatarBubble(name, sizeDp = 142, ring = Color.Transparent) }
            }
            Text(name, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Ui.Text)
            Spacer(Modifier.height(4.dp))
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
                color = if (call.phase == CallPhase.RECONNECTING || call.phase == CallPhase.FAILED) LinkColors.Warn else Color(0xFFC9BDE0),
                fontSize = 15.sp,
            )
            if (live) {
                Spacer(Modifier.height(16.dp))
                QualityBars(stats)
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            if (call.phase.hasMedia && route.available.size > 1) {
                Text("Audio: ${route.current?.label ?: "-"}", color = Color(0xFFC9BDE0), fontSize = 12.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(10.dp))
            }
            if (call.phase != CallPhase.ENDED && call.phase != CallPhase.FAILED && call.phase != CallPhase.ENDING) Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp), Color(0x12FFFFFF), Color(0x1AFFFFFF)).padding(vertical = 18.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (call.phase == CallPhase.INCOMING_RINGING) {
                    RoundButton(R.drawable.ic_call_end, "Decline", Ui.Red) { vm.reject() }
                    RoundButton(null, "Answer", Color(0xFF22C55E)) {
                        gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.accept() }
                    }
                } else {
                    if (call.phase.hasMedia) {
                        RoundButton(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic, if (muted) "Unmute" else "Mute",
                            if (muted) LinkColors.Warn else Color(0x1FFFFFFF)) { vm.setMuted(!muted) }
                        RoundButton(R.drawable.ic_speaker, route.current?.label ?: "Audio", Color.White, iconTint = Color(0xFF1A1030)) {
                            val all = route.available
                            if (all.isNotEmpty()) vm.selectRoute(all[(all.indexOf(route.current) + 1) % all.size])
                        }
                    }
                    RoundButton(R.drawable.ic_call_end, "End", Ui.Red) { vm.hangup() }
                }
            }
        }
    }
}

@Composable
private fun RoundButton(icon: Int?, label: String, color: Color, iconTint: Color = Color.White, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier.size(62.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = color, contentColor = iconTint),
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(26.dp))
            else Icon(Icons.Default.Call, contentDescription = label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFFC9BDE0), maxLines = 1)
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
