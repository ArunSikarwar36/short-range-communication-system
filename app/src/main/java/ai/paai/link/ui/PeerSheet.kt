package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.core.location.Geo
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.util.DeviceId
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Everything you can do with one connected phone: call, walkie-talkie, message, location, map,
 * DJ, rename and unpair. Opened by tapping a person on the radar or in the Team list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerSheet(
    vm: LinkViewModel,
    gate: PermissionGate,
    peerId: DeviceId,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    onTab: (String) -> Unit,
) {
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val friends by vm.friendLocations.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val p = peers.firstOrNull { it.id == peerId } ?: return
    val f = friends.firstOrNull { it.id == peerId }
    val m = me
    var renaming by remember { mutableStateOf(false) }
    var unpairing by remember { mutableStateOf(false) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val done = { action: () -> Unit -> onDismiss(); action() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Color(0xFF111720), scrimColor = Color(0x8C000000)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Who.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AvatarBubble(p.name, sizeDp = 60, ring = Color.Transparent)
                    if (p.reachable) Box(
                        Modifier.align(Alignment.BottomEnd).size(16.dp).background(Color(0xFF111720), CircleShape).padding(3.dp)
                            .background(LinkColors.Good, CircleShape),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = Ui.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val where = if (f != null && m != null) {
                        " · " + Geo.formatDistance(Geo.distanceM(m.lat, m.lon, f.lat, f.lon)) + " " + Geo.compass(Geo.bearingDeg(m.lat, m.lon, f.lat, f.lon))
                    } else ""
                    Text(
                        when {
                            !p.reachable -> "Out of range"
                            p.direct -> "Connected directly$where"
                            else -> "Through another phone · ${p.hops ?: 2} hops$where"
                        },
                        color = Ui.Sub, fontSize = 13.sp,
                    )
                }
                if (p.reachable) {
                    val (label, col) = if (p.direct) "Direct" to LinkColors.Good else "Relay" to LinkColors.Accent
                    Text(label, color = col, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.background(col.copy(alpha = 0.15f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp))
                }
            }

            // Main actions.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryPill(
                    "Call", Modifier.weight(1f), rememberVectorPainter(Icons.Default.Call), height = 54.dp,
                    colors = listOf(Color(0xFF16A34A), Color(0xFF3DDC97)), enabled = p.reachable,
                ) { done { gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.call(p.id) } } }
                PrimaryPill(
                    if (group != null) "Team" else "Walkie-talkie", Modifier.weight(1f), painterResource(R.drawable.ic_mic), height = 54.dp,
                    enabled = p.reachable,
                ) {
                    done {
                        val g = group
                        when {
                            g == null -> gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) {
                                vm.createGroup("Walkie-talkie", GroupMode.PTT, listOf(p.id))
                                onTab("home")
                            }
                            g.members.none { it.id == p.id } && p.id !in g.invited -> { vm.inviteToGroup(p.id); onTab("team") }
                            else -> onTab("team")
                        }
                    }
                }
            }

            // Secondary actions.
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Tile("Message", painterResource(R.drawable.ic_chat), ChatAmber, Modifier.weight(1f)) { done { onNavigate("chat/${p.id.hex}") } }
                Tile(
                    if (sharing.active) "Stop sharing" else "Share location", rememberVectorPainter(Icons.Default.LocationOn), LinkColors.Good, Modifier.weight(1f),
                ) {
                    done {
                        if (sharing.active) vm.stopSharing()
                        else gate.withPermissions(Perms.location, Reasons.LOCATION) {
                            vm.startSharing(toPaired = true, toGroup = group != null, minutes = 60)
                            vm.notify("Sharing your location with your paired phones for 1 hour")
                        }
                    }
                }
                Tile("On the map", painterResource(R.drawable.ic_map), LinkColors.Accent, Modifier.weight(1f)) {
                    done {
                        if (f == null) vm.notify("${p.name} is not sharing a location yet")
                        else if (vm.hasMapFor(f.lat, f.lon)) onNavigate("map?lat=${f.lat}&lon=${f.lon}")
                        else openInMapApp(context, f.lat, f.lon, p.name)
                    }
                }
                Tile("DJ Mode", painterResource(R.drawable.ic_music), Ui.Purple, Modifier.weight(1f)) {
                    done {
                        if (group == null) vm.notify("Start a walkie-talkie team first, then DJ Mode plays music to everyone in it")
                        onTab("team")
                    }
                }
            }

            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), Color(0x0AFFFFFF), Color(0x12FFFFFF))) {
                if (p.reachable) {
                    SheetRow("Disconnect", Ui.Text) { done { vm.disconnectAll() } }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0x0FFFFFFF)))
                }
                SheetRow("Rename", Ui.Text) { renaming = true }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0x0FFFFFFF)))
                SheetRow("Unpair", Color(0xFFFF6B72)) { unpairing = true }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, label = { Text("Name on this phone") }) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(p.id, name) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (unpairing) {
        AlertDialog(
            onDismissRequest = { unpairing = false },
            title = { Text("Unpair ${p.name}?") },
            text = { Text("You will have to compare a pairing code again before you can call or message each other.") },
            confirmButton = { TextButton(onClick = { unpairing = false; onDismiss(); vm.unpair(p.id) }) { Text("Unpair", color = LinkColors.Bad) } },
            dismissButton = { TextButton(onClick = { unpairing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Tile(label: String, icon: Painter, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.glass(RoundedCornerShape(18.dp), Color(0x0DFFFFFF), Color(0x14FFFFFF)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = Color(0xFFD6DBE1), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SheetRow(label: String, color: Color, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text("›", color = Color(0xFF7B8A99), fontSize = 18.sp)
    }
}
