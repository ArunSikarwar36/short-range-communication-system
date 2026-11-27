package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.core.location.Geo
import ai.paai.link.core.util.DeviceId
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Team tab: purple header, nearby phones with distance, then the team / walkie-talkie / DJ controls. */
@Composable
fun TeamTab(vm: LinkViewModel, gate: PermissionGate, onChat: (DeviceId) -> Unit, onNavigate: (String) -> Unit, onTab: (String) -> Unit) {
    var sheetFor by remember { mutableStateOf<DeviceId?>(null) }
    Box(
        Modifier.fillMaxSize().background(
            Brush.radialGradient(listOf(Ui.PurpleDeep, Color(0xFF0B0D14), Color(0xFF07090D)), center = androidx.compose.ui.geometry.Offset(540f, 0f), radius = 1500f),
        ),
    ) {
        GroupScreen(vm, gate, onBack = null, onChat = onChat, header = { TeamHeader(vm, gate) { sheetFor = it } })
    }
    sheetFor?.let { PeerSheet(vm, gate, it, onDismiss = { sheetFor = null }, onNavigate = onNavigate, onTab = onTab) }
}

@Composable
private fun TeamHeader(vm: LinkViewModel, gate: PermissionGate, onPerson: (DeviceId) -> Unit) {
    val online by vm.online.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val friends by vm.friendLocations.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    var joinIphone by remember { mutableStateOf(false) }
    val geo = friends.associateBy { it.id }
    val connected = peers.filter { it.trusted && it.reachable }
    val pairing = links.filter { !it.trusted }
    val nearbyP2p = wifi.peers.filter { it.statusLabel != "Connected" }
    fun distanceTo(id: DeviceId): Double? {
        val f = geo[id] ?: return null
        val m = me ?: return null
        return Geo.distanceM(m.lat, m.lon, f.lat, f.lon)
    }
    val farthest = connected.mapNotNull { distanceTo(it.id) }.maxOrNull()
    val scan = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.scan() } }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Title + scan.
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Team", color = Color(0xFFA99BC4), fontSize = 13.sp)
                Text(group?.name ?: "Nearby", color = Ui.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (online) GlassButton(if (wifi.discovering) "Scanning…" else "Scan", icon = painterResource(R.drawable.ic_radar)) { scan() }
        }

        invites.forEach { inv ->
            GradientCard("INVITATION", listOf(Color(0x8014B8A6), Color(0x590E7490)), Color(0x5514B8A6)) {
                Text("${inv.fromName} invited you to '${inv.name}'", color = Color.White, fontWeight = FontWeight.Bold)
                Text(if (inv.mode.name == "PTT") "Walkie-talkie team" else "Group call", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryPill("Join", Modifier.weight(1f), height = 44.dp, colors = listOf(TeamTeal, Color(0xFF0EA5A0))) {
                        gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.acceptInvite(inv.groupId) }
                    }
                    GlassButton("Decline", Modifier.weight(1f)) { vm.declineInvite(inv.groupId) }
                }
            }
        }

        // Stats.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("${connected.size + pairing.size + nearbyP2p.size}", "nearby", Ui.Text, Modifier.weight(1f))
            Stat("${connected.size}", "connected", LinkColors.Good, Modifier.weight(1f))
            Stat(farthest?.let { Geo.formatDistance(it) } ?: "—", "farthest", Ui.Text, Modifier.weight(1f))
        }

        // Nearby phones.
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), Color(0xC714121F), Color(0x14FFFFFF)).padding(vertical = 6.dp)) {
            var first = true
            @Composable
            fun divider() {
                if (!first) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0x0FFFFFFF)))
                first = false
            }
            connected.forEach { p ->
                divider()
                val d = distanceTo(p.id)
                PersonRow(
                    p.name, online = true,
                    sub = if (p.direct) "Direct · connected" else "Through ${p.via?.let { v -> peers.firstOrNull { it.id == v }?.name } ?: "a relay"} · ${p.hops ?: 2} hops",
                    onClick = { onPerson(p.id) },
                ) {
                    if (d != null) Text(Geo.formatDistance(d), color = Color(0xFFE9D5FF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    if (!p.direct) Pill("Relay", Color(0xFF67E8F9), LinkColors.Accent)
                    Spacer(Modifier.width(8.dp))
                    RoundIcon(
                        rememberVectorPainter(Icons.Default.Call), "Call ${p.name}", LinkColors.Good, size = 38.dp,
                        background = LinkColors.Good.copy(alpha = 0.16f), line = Color.Transparent,
                    ) { gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.call(p.id) } }
                }
            }
            pairing.forEach { l ->
                divider()
                PersonRow(l.remoteName, online = false, sub = "Compare the pairing code on both phones", onClick = {}) {
                    Pill("Pairing", LinkColors.Warn, LinkColors.Warn)
                }
            }
            nearbyP2p.forEach { p ->
                divider()
                PersonRow(p.name, online = false, sub = "Nearby · ${p.statusLabel}", onClick = { vm.connectP2p(p) }) {
                    Box(
                        Modifier.height(34.dp).background(LinkColors.Accent, RoundedCornerShape(17.dp)).clickable { vm.connectP2p(p) }.padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Connect", color = Color(0xFF04222B), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                }
            }
            if (connected.isEmpty() && pairing.isEmpty() && nearbyP2p.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (online) "No phones found yet" else "You're offline", color = Ui.Text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            !online -> "Go online on the Home tab to find phones nearby."
                            wifi.discovering -> "Scanning… keep SRCS open on the other phone and tap Scan there too."
                            else -> "Tap Scan on both phones. One taps Connect, the other accepts."
                        },
                        color = Ui.Sub, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (online && wifi.supported) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                wifi.group.isOwner && wifi.group.passphrase != null -> {
                    GlassButton("Add an iPhone", Modifier.weight(1f)) { joinIphone = true }
                    GlassButton("Disconnect", Modifier.weight(1f)) { vm.disconnectAll() }
                }
                wifi.group.formed -> GlassButton("Disconnect", Modifier.weight(1f)) { vm.disconnectAll() }
                else -> GlassButton("Host a group", Modifier.weight(1f)) { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.hostGroup() } }
            }
        }
        wifi.lastError?.let { Text(it, color = LinkColors.Warn, fontSize = 12.sp) }
    }
    val n = wifi.group.networkName
    val p = wifi.group.passphrase
    if (joinIphone && n != null && p != null) JoinIphoneDialog(n, p) { joinIphone = false }
}

@Composable
private fun Stat(value: String, label: String, color: Color, modifier: Modifier) {
    Column(modifier.glass(RoundedCornerShape(18.dp), Color(0x0FFFFFFF), Color(0x14FFFFFF)).padding(12.dp)) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Ui.Sub, fontSize = 12.sp)
    }
}

@Composable
private fun Pill(text: String, fg: Color, bg: Color) {
    Text(
        text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 6.dp).background(bg.copy(alpha = 0.16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PersonRow(name: String, online: Boolean, sub: String, onClick: () -> Unit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            AvatarBubble(name, sizeDp = 44, ring = Color.Transparent)
            if (online) Box(
                Modifier.align(Alignment.BottomEnd).size(13.dp).background(Color(0xFF141220), CircleShape).padding(2.dp).background(LinkColors.Good, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Ui.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, color = Ui.Sub, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}
