package ai.paai.link.ui

import ai.paai.link.core.location.Geo
import ai.paai.link.core.util.DeviceId
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Team tab: nearby devices (with distance) above the team / walkie-talkie / DJ controls. */
@Composable
fun TeamTab(vm: LinkViewModel, gate: PermissionGate, onChat: (DeviceId) -> Unit) {
    GroupScreen(vm, gate, onBack = null, onChat = onChat, header = { TeamHeader(vm, gate, onChat) })
}

@Composable
private fun TeamHeader(vm: LinkViewModel, gate: PermissionGate, onChat: (DeviceId) -> Unit) {
    val online by vm.online.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val friends by vm.friendLocations.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    var joinIphone by remember { mutableStateOf(false) }
    val geo = friends.associateBy { it.id }
    val connected = peers.filter { it.trusted && it.reachable }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        invites.forEach { inv ->
            Column(
                Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(TeamTeal, Color(0xFF0E7490))), RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("${inv.fromName} invited you to '${inv.name}'", color = Color.White, fontWeight = FontWeight.Bold)
                Text(if (inv.mode.name == "PTT") "Walkie-talkie team" else "Group call", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.acceptInvite(inv.groupId) } }) { Text("Join") }
                    OutlinedButton(onClick = { vm.declineInvite(inv.groupId) }) { Text("Decline", color = Color.White) }
                }
            }
        }

        // Nearby devices, as in the P.A.A.I. Link mockup: name, connection and distance, status pill.
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF0F1B2B), RoundedCornerShape(20.dp)).border(1.dp, Color(0x2222C7E6), RoundedCornerShape(20.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nearby devices", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White, modifier = Modifier.weight(1f))
                Text("${connected.size} CONNECTED", color = LinkColors.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            if (!online) Hint("Go online on the Home tab to find phones nearby.")
            connected.forEach { p ->
                val f = geo[p.id]
                val m = me
                val dist = if (f != null && m != null) " · " + Geo.formatDistance(Geo.distanceM(m.lat, m.lon, f.lat, f.lon)) else ""
                DeviceRow(p.name, (if (p.direct) "Connected" else "Via relay · ${p.hops ?: 2} hops") + dist, "Online", LinkColors.Good) { onChat(p.id) }
            }
            links.filter { !it.trusted }.forEach { l -> DeviceRow(l.remoteName, "Compare the pairing code", "Pairing", LinkColors.Warn) {} }
            wifi.peers.filter { it.statusLabel != "Connected" }.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AvatarBubble(p.name, sizeDp = 36)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, color = Color.White, fontWeight = FontWeight.SemiBold)
                        Hint("Nearby · ${p.statusLabel}")
                    }
                    Button(onClick = { vm.connectP2p(p) }) { Text("Connect") }
                }
            }
            if (online && connected.isEmpty() && wifi.peers.isEmpty() && links.isEmpty()) {
                Hint(if (wifi.discovering) "Scanning… keep SRCS open on the other phone and tap scan there too." else "Tap Scan on both phones. One taps Connect, the other accepts.")
            }
            if (online) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.scan() } }) { Text(if (wifi.discovering) "Scanning…" else "Scan") }
                if (wifi.supported && !wifi.group.formed) {
                    OutlinedButton(onClick = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.hostGroup() } }) { Text("Host a group") }
                }
                if (wifi.group.isOwner && wifi.group.passphrase != null) OutlinedButton(onClick = { joinIphone = true }) { Text("Add iPhone") }
                if (wifi.group.formed) TextButton(onClick = { vm.leaveP2p() }) { Text("Leave Wi-Fi Direct group") }
            }
            wifi.lastError?.let { Text(it, color = LinkColors.Warn, fontSize = 12.sp) }
        }
    }
    val n = wifi.group.networkName
    val p = wifi.group.passphrase
    if (joinIphone && n != null && p != null) JoinIphoneDialog(n, p) { joinIphone = false }
}

@Composable
private fun DeviceRow(name: String, status: String, pill: String, pillColor: Color, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarBubble(name, sizeDp = 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontWeight = FontWeight.SemiBold)
            Hint(status)
        }
        TextButton(onClick = onClick) {
            Text(pill, color = pillColor, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(pillColor.copy(alpha = 0.15f), RoundedCornerShape(10.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
        }
    }
}
