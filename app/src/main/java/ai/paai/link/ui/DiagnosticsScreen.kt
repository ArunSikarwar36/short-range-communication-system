package ai.paai.link.ui

import ai.paai.link.audio.OpusNative
import ai.paai.link.core.media.MediaStats
import ai.paai.link.core.node.LogLevel
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.net.NetworkInterface
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: LinkViewModel, onBack: () -> Unit) {
    val stack by vm.stack.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val routes by vm.routes.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val aware by vm.aware.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    val callSession by vm.callSession.collectAsStateWithLifecycle()
    val groupSession by vm.groupSession.collectAsStateWithLifecycle()
    val fingerprint by vm.fingerprint.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    var stats by remember { mutableStateOf<MediaStats?>(null) }
    val session = callSession ?: groupSession

    LaunchedEffect(session) {
        while (true) {
            stats = session?.let { it.refresh(); it.stats.value }
            delay(1_000)
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Diagnostics") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("This device") {
                    val node = stack?.node
                    Mono("Device ID   ${node?.selfId?.hex ?: "(offline)"}")
                    Mono("Fingerprint ${fingerprint ?: "…"}")
                    Mono("Ports       tcp ${node?.tcpPort ?: "-"} · udp ${node?.udpPort ?: "-"}")
                    Mono("Addresses   ${localAddresses()}")
                    Mono("Relaying    ${if (node == null) "-" else if (node.relayEnabled) "on" else if (vm.c.relayPausedForBattery) "paused (low battery)" else "off"}")
                    Mono("Opus        ${runCatching { OpusNative.version() }.getOrElse { "unavailable" }}")
                }
            }
            item {
                SectionCard("Wi-Fi Direct") {
                    Mono("Supported   ${wifi.supported}   Enabled ${wifi.enabled}   Scanning ${wifi.discovering}")
                    Mono("Group       ${if (!wifi.group.formed) "none" else if (wifi.group.isOwner) "owner (${wifi.group.clientCount} clients)" else "client of ${wifi.group.ownerAddress}"}")
                    wifi.group.networkName?.let { Mono("Network     $it") }
                    Mono("Peers seen  ${wifi.peers.size}")
                    wifi.lastError?.let { Text(it, color = LinkColors.Warn, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                SectionCard("Wi-Fi Aware") {
                    Mono("Supported   ${aware.supported}   Available ${aware.available}   Running ${aware.running}")
                    Mono("Peers seen  ${aware.peers.size}   Data paths ${aware.dataPaths}/${aware.maxDataPaths}")
                    if (!aware.supported) Hint("This phone has no Wi-Fi Aware (or runs Android 9 or older). SRCS uses Wi-Fi Direct instead.")
                    aware.lastError?.let { Text(it, color = LinkColors.Warn, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                SectionCard("Connect by address") {
                    Hint("For phones on the same hotspot or local network, or to retry a link manually. Format: 192.168.49.1 or host:port")
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedTextField(address, { address = it.trim().take(64) }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Address") })
                        Button(onClick = { vm.connectByAddress(address) }, enabled = stack != null && address.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Connect") }
                    }
                }
            }
            item {
                SectionCard("Links (${links.size})") {
                    if (links.isEmpty()) Hint("No direct links.")
                    links.forEach { l ->
                        Mono("${l.remoteName} ${if (l.trusted) "✓ paired" else "… pairing (code ${l.sas})"}")
                        Mono("  ${l.address} · ${if (l.initiator) "outgoing" else "incoming"} · rtt ${l.rttMs?.let { "$it ms" } ?: "?"} · ↑${l.bytesSent / 1024} KB ↓${l.bytesReceived / 1024} KB${if (l.relayCapable) " · relay" else ""}")
                    }
                }
            }
            item {
                SectionCard("Network topology") {
                    val selfName = stack?.node?.displayName ?: "This phone"
                    TopologyView(selfName, routes.map { Triple(it.name, routes.firstOrNull { r -> r.dest == it.nextHop }?.name, it.hops) })
                    if (routes.isEmpty()) Hint("No reachable devices.")
                    routes.forEach { r ->
                        Mono("${r.name.padEnd(14).take(14)} ${r.hops} hop${if (r.hops > 1) "s" else " "} via ${if (r.hops == 1) "direct" else routes.firstOrNull { it.dest == r.nextHop }?.name ?: r.nextHop.short}  seq ${r.seq}")
                    }
                }
            }
            stats?.let { s ->
                item {
                    SectionCard("Live audio") {
                        Mono("Sent ${s.packetsSent} · received ${s.packetsReceived} · forwarded ${s.forwarded}")
                        Mono("Loss ${"%.2f".format(s.lossPercent)}% · late ${s.latePackets} · jitter ${s.jitterMs} ms · buffer ${s.bufferMs} ms")
                        Mono("Bitrate ${s.bitrate / 1000} kbps · auth failures ${s.authFailures} · send failures ${s.sendFailures}")
                        Hint("One-way latency ≈ network delay + jitter buffer (${s.bufferMs} ms) + ~40 ms audio I/O. Measure end-to-end latency on real phones.")
                    }
                }
            }
            item { Text("Log", style = MaterialTheme.typography.titleMedium) }
            items(logs.asReversed().take(200)) { line ->
                Text(
                    "${formatTime(line.at)} ${line.tag}: ${line.message}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = when (line.level) { LogLevel.ERROR -> LinkColors.Bad; LogLevel.WARN -> LinkColors.Warn; else -> LinkColors.Muted },
                )
            }
        }
    }
}

@Composable
private fun Mono(text: String) = Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))

/** Radial graph: this phone in the centre, direct neighbours on the inner ring, relayed devices outside. */
@Composable
private fun TopologyView(selfName: String, nodes: List<Triple<String, String?, Int>>) {
    val measurer = rememberTextMeasurer()
    val color = LinkColors.Accent
    val muted = LinkColors.Muted
    val text = TextStyle(color = LinkColors.OnSurface, fontSize = 11.sp)
    Canvas(
        Modifier.fillMaxWidth().height(220.dp).semantics {
            contentDescription = "Topology: ${nodes.joinToString { "${it.first} ${it.third} hops" }.ifEmpty { "no devices" }}"
        },
    ) {
        val c = Offset(size.width / 2, size.height / 2)
        val positions = HashMap<String, Offset>()
        positions[selfName] = c
        val rings = nodes.groupBy { it.third.coerceAtMost(3) }
        for ((hops, list) in rings) {
            val radius = (size.minDimension / 2 - 24) * hops / 3f
            list.forEachIndexed { i, n ->
                val a = (2 * Math.PI * i / list.size + hops * 0.6).toFloat()
                positions[n.first] = Offset(c.x + radius * cos(a), c.y + radius * sin(a))
            }
        }
        for (n in nodes) {
            val from = if (n.third == 1) c else positions[n.second] ?: c
            val to = positions[n.first] ?: continue
            drawLine(if (n.third == 1) color else muted, from, to, strokeWidth = 3f)
        }
        drawCircle(color, 14f, c)
        drawCircle(color, 22f, c, style = Stroke(2f))
        drawText(measurer, selfName.take(12), Offset(c.x + 18, c.y - 8), text)
        for (n in nodes) {
            val p = positions[n.first] ?: continue
            drawCircle(if (n.third == 1) color else muted, 9f, p)
            drawText(measurer, n.first.take(12), Offset(p.x + 12, p.y - 8), text)
        }
    }
}

private fun localAddresses(): String = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { i -> i.inetAddresses.toList().filter { it is java.net.Inet4Address }.map { "${i.name}=${it.hostAddress}" } }
        .joinToString(", ").ifEmpty { "none" }
}.getOrDefault("unknown")
