package ai.paai.link.ui

import ai.paai.link.core.app.RadarDot
import ai.paai.link.core.app.RadarEntry
import ai.paai.link.core.app.RadarLayout
import ai.paai.link.core.app.RadarRing
import ai.paai.link.core.util.DeviceId
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.cos
import kotlin.math.sin

private fun ringColor(r: RadarRing) = when (r) {
    RadarRing.LINKED -> LinkColors.Good
    RadarRing.RELAYED -> LinkColors.Accent
    RadarRing.NEARBY -> LinkColors.Muted
}

/**
 * Nearby radar: this phone in the centre, linked phones on the inner ring, relayed phones on the
 * middle ring and phones that are nearby but not linked (Wi-Fi Direct / Wi-Fi Aware discovery,
 * waiting pairings) on the outer ring. Tap a phone for actions.
 */
@Composable
fun RadarCard(vm: LinkViewModel, gate: PermissionGate, onChat: (DeviceId) -> Unit) {
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val aware by vm.aware.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<RadarDot?>(null) }

    val p2pByKey = wifi.peers.filter { it.statusLabel != "Connected" }.associateBy { "p2p:${it.address}" }
    val entries = buildList {
        peers.filter { it.trusted && it.reachable }.forEach {
            add(RadarEntry(it.id.hex, it.name, if (it.direct) RadarRing.LINKED else RadarRing.RELAYED, peerId = it.id.hex))
        }
        links.filter { !it.trusted }.forEach { add(RadarEntry("link:${it.linkId}", it.remoteName, RadarRing.NEARBY, pairingLinkId = it.linkId)) }
        p2pByKey.forEach { (k, p) -> add(RadarEntry(k, p.name, RadarRing.NEARBY)) }
        aware.peers.filter { !it.linked }.forEach { add(RadarEntry("aware:${it.key}", "SRCS phone", RadarRing.NEARBY)) }
    }
    val dots = remember(entries) { RadarLayout.place(entries) }
    val linked = dots.count { it.ring == RadarRing.LINKED }
    val relayed = dots.count { it.ring == RadarRing.RELAYED }
    val nearby = dots.count { it.ring == RadarRing.NEARBY }

    SectionCard("Nearby radar") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.widthIn(max = 360.dp).fillMaxWidth().aspectRatio(1f)) {
                val half = maxWidth / 2
                RadarBackground()
                // This phone.
                Box(
                    Modifier.offset(half - 26.dp, half - 26.dp).size(52.dp)
                        .background(LinkColors.Accent, CircleShape).border(3.dp, LinkColors.Background, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("You", color = LinkColors.Background, fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                dots.forEach { d ->
                    val rad = Math.toRadians(d.angleDeg.toDouble() - 90.0)
                    val x = half + half * (d.radius * cos(rad)).toFloat()
                    val y = half + half * (d.radius * sin(rad)).toFloat()
                    Column(
                        Modifier.offset(x - 32.dp, y - 19.dp).width(64.dp).clickable { selected = d }
                            .semantics { contentDescription = "${d.name}, ${ringLabel(d.ring)}" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.size(38.dp).background(ringColor(d.ring).copy(alpha = 0.22f), CircleShape)
                                .border(2.dp, ringColor(d.ring), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Text(d.name.trim().take(1).uppercase().ifEmpty { "?" }, fontWeight = FontWeight.Bold, color = LinkColors.OnSurface) }
                        Text(d.name, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = LinkColors.OnSurface)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Legend(LinkColors.Good, "Linked $linked")
            Legend(LinkColors.Accent, "Via relay $relayed")
            Legend(LinkColors.Muted, "Nearby $nearby")
        }
        if (dots.isEmpty()) {
            Hint(
                if (aware.running) "Looking for SRCS phones over Wi-Fi Aware… Scan to also search with Wi-Fi Direct."
                else "No phones nearby yet. Tap scan below, or host a group.",
            )
        }
    }

    selected?.let { d ->
        val p2p = p2pByKey[d.key]
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(d.name) },
            text = {
                Text(
                    when {
                        d.peerId.isNotEmpty() && d.ring == RadarRing.LINKED -> "Linked directly."
                        d.peerId.isNotEmpty() -> "Reached through another phone (relay)."
                        d.pairingLinkId >= 0 -> "Waiting for both phones to confirm the pairing code."
                        p2p != null -> "Nearby over Wi-Fi Direct. Connect to link and pair."
                        else -> "Nearby over Wi-Fi Aware. SRCS links automatically and then asks you to compare a pairing code."
                    },
                )
            },
            confirmButton = {
                when {
                    d.peerId.isNotEmpty() -> TextButton(onClick = {
                        selected = null
                        gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.call(DeviceId.fromHex(d.peerId)) }
                    }) { Text("Call") }
                    p2p != null -> TextButton(onClick = { selected = null; vm.connectP2p(p2p) }) { Text("Connect") }
                    else -> TextButton(onClick = { selected = null }) { Text("OK") }
                }
            },
            dismissButton = {
                if (d.peerId.isNotEmpty()) TextButton(onClick = { selected = null; onChat(DeviceId.fromHex(d.peerId)) }) { Text("Message") }
            },
        )
    }
}

private fun ringLabel(r: RadarRing) = when (r) {
    RadarRing.LINKED -> "linked"
    RadarRing.RELAYED -> "via relay"
    RadarRing.NEARBY -> "nearby, not linked"
}

@Composable
private fun RadarBackground() {
    val t = rememberInfiniteTransition(label = "radar")
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(LinkColors.Accent.copy(alpha = 0.16f), Color.Transparent), c, r), r, c)
        for (ring in RadarRing.entries) {
            drawCircle(LinkColors.AccentDim.copy(alpha = 0.7f), r * ring.radius, c, style = Stroke(1.dp.toPx()))
        }
        // Expanding pulse from this phone.
        drawCircle(LinkColors.Accent.copy(alpha = (1 - pulse) * 0.45f), r * pulse, c, style = Stroke(2.dp.toPx()))
        // Rotating sweep line.
        val a = Math.toRadians(sweep.toDouble() - 90.0)
        drawLine(
            Brush.linearGradient(listOf(Color.Transparent, LinkColors.Accent.copy(alpha = 0.5f)), c, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())),
            c, Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), strokeWidth = 2.dp.toPx(),
        )
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, color = LinkColors.Muted)
    }
}
