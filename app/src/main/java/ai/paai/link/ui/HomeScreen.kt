package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.core.call.EndReason
import ai.paai.link.core.node.PeerStatus
import ai.paai.link.core.util.DeviceId
import ai.paai.link.data.PeerEntity
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: LinkViewModel, gate: PermissionGate, onNavigate: (String) -> Unit) {
    val online by vm.online.collectAsStateWithLifecycle()
    val busy by vm.transitioning.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val recent by vm.recentCalls.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val unread = conversations.sumOf { it.unread }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("SRCS", fontWeight = FontWeight.Bold)
                    Hint("Short Range Communication · " + settings.displayName.ifBlank { "This phone" })
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
            actions = {
                IconButton(onClick = { onNavigate("chats") }) {
                    BadgedBox(badge = { if (unread > 0) Badge { Text("$unread") } }) {
                        Icon(painterResource(R.drawable.ic_chat), contentDescription = "Messages")
                    }
                }
                IconButton(onClick = { onNavigate("group") }) { Icon(painterResource(R.drawable.ic_group), contentDescription = "Group and walkie-talkie") }
                IconButton(onClick = { onNavigate("diagnostics") }) { Icon(Icons.Default.Info, contentDescription = "Diagnostics") }
                IconButton(onClick = { onNavigate("settings") }) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
            },
        )
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Status", action = {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Switch(
                        checked = online,
                        onCheckedChange = { on ->
                            if (on) gate.withPermissions(emptyArray(), Reasons.NOTIFY, Perms.notifications) { vm.goOnline() }
                            else vm.goOffline()
                        },
                    )
                }) {
                    when {
                        !online -> StatusLine(LinkColors.Muted, "Offline", "Turn on to find and talk to nearby phones. No internet is used.")
                        else -> {
                            val trusted = links.count { it.trusted }
                            StatusLine(
                                if (trusted > 0) LinkColors.Good else LinkColors.Warn,
                                if (trusted > 0) "Online · $trusted linked" else "Online · not linked",
                                when {
                                    !wifi.supported -> "This phone does not support Wi-Fi Direct. You can still link over a shared hotspot (Diagnostics → Connect by address)."
                                    wifi.group.formed && wifi.group.isOwner -> "Hosting a Wi-Fi Direct group (${wifi.group.clientCount} joined)"
                                    wifi.group.formed -> "Joined a Wi-Fi Direct group"
                                    !wifi.enabled -> "Wi-Fi is off — turn it on to discover devices"
                                    else -> "Scan to find nearby phones"
                                },
                            )
                        }
                    }
                    group?.let { g ->
                        FilledTonalButton(onClick = { onNavigate("group") }, modifier = Modifier.fillMaxWidth()) {
                            Text("${if (g.mode.name == "PTT") "Walkie-talkie" else "Group call"} '${g.name}' · ${g.members.size} members")
                        }
                    }
                }
            }

            if (online) item { SosAlerts(vm) }

            if (online) item { RadarCard(vm, gate, onChat = { onNavigate("chat/${it.hex}") }) }

            if (online) item { LocationCard(vm, gate) }

            if (invites.isNotEmpty()) item {
                SectionCard("Invitations") {
                    invites.forEach { inv ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${inv.fromName} invited you", style = MaterialTheme.typography.bodyMedium)
                                Hint("'${inv.name}' · ${if (inv.mode.name == "PTT") "walkie-talkie" else "group call"}")
                            }
                            TextButton(onClick = { vm.declineInvite(inv.groupId) }) { Text("Decline") }
                            Button(onClick = {
                                gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) {
                                    vm.acceptInvite(inv.groupId)
                                    onNavigate("group")
                                }
                            }) { Text("Join") }
                        }
                    }
                }
            }

            if (online) item { NearbyCard(vm, gate) }

            item { PairedCard(vm, gate, online, paired, peers, onChat = { onNavigate("chat/${it.hex}") }) }

            if (recent.isNotEmpty()) item {
                SectionCard("Recent calls") {
                    recent.take(6).forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val missed = c.result == EndReason.MISSED.name
                            Column(Modifier.weight(1f)) {
                                Text(c.peerName, color = if (missed) LinkColors.Bad else LinkColors.OnSurface)
                                Hint(
                                    (if (c.outgoing) "Outgoing" else "Incoming") + " · " +
                                        (if (c.durationMs > 0) formatDuration(c.durationMs) else runCatching { EndReason.valueOf(c.result).label }.getOrDefault(c.result)) +
                                        " · " + formatTime(c.startedAt),
                                )
                            }
                            IconButton(onClick = { gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.call(DeviceId.fromHex(c.peerId)) } }) {
                                Icon(Icons.Default.Call, contentDescription = "Call ${c.peerName}")
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.size(24.dp)) }
        }
    }
}

@Composable
private fun NearbyCard(vm: LinkViewModel, gate: PermissionGate) {
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    var showJoin by remember { mutableStateOf(false) }
    SectionCard("Nearby devices", action = {
        if (wifi.supported) {
            if (wifi.discovering) TextButton(onClick = { vm.stopScan() }) { Text("Stop") }
            else IconButton(onClick = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.scan() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Scan for nearby devices")
            }
        }
    }) {
        wifi.lastError?.let {
            Text(it, color = LinkColors.Warn, style = MaterialTheme.typography.bodySmall)
            if (Build.VERSION.SDK_INT < 33) Hint("On Android 12 and older, Location must also be switched on for Wi-Fi Direct scanning.")
        }
        if (wifi.discovering) Hint("Scanning… keep SRCS open on the other phone too.")
        if (wifi.peers.isEmpty() && !wifi.discovering) Hint("No devices found yet. Tap scan on both phones.")
        wifi.peers.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.name)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name)
                    Hint(p.statusLabel)
                }
                if (p.statusLabel == "Connected") OutlinedButton(onClick = { vm.leaveP2p() }) { Text("Disconnect") }
                else Button(onClick = { vm.connectP2p(p) }) { Text("Connect") }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (wifi.supported && !wifi.group.formed) {
                OutlinedButton(onClick = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.hostGroup() } }) { Text("Host a group") }
            }
            if (wifi.group.formed) OutlinedButton(onClick = { vm.leaveP2p() }) { Text("Leave Wi-Fi Direct group") }
            if (wifi.group.isOwner && wifi.group.networkName != null && wifi.group.passphrase != null) {
                OutlinedButton(onClick = { showJoin = true }) { Text("Add an iPhone") }
            }
        }
        val joinName = wifi.group.networkName
        val joinPass = wifi.group.passphrase
        val canJoin = wifi.group.isOwner && joinName != null && joinPass != null
        LaunchedEffect(canJoin) { if (!canJoin) showJoin = false }
        if (showJoin && joinName != null && joinPass != null) JoinIphoneDialog(joinName, joinPass) { showJoin = false }
        if (wifi.group.formed) Hint(
            if (wifi.group.isOwner) "This phone is the group owner. Others connect to it; it relays calls between them."
            else "Connected to group owner ${wifi.group.ownerAddress ?: ""}",
        )
        val pending = links.filter { !it.trusted }
        if (pending.isNotEmpty()) Hint("Waiting for pairing with ${pending.joinToString { it.remoteName }}")
    }
}

@Composable
private fun PairedCard(
    vm: LinkViewModel,
    gate: PermissionGate,
    online: Boolean,
    paired: List<PeerEntity>,
    peers: List<PeerStatus>,
    onChat: (DeviceId) -> Unit,
) {
    val status = peers.associateBy { it.id.hex }
    var renaming by remember { mutableStateOf<PeerEntity?>(null) }
    var removing by remember { mutableStateOf<PeerEntity?>(null) }
    SectionCard("Paired devices") {
        if (paired.isEmpty()) Hint("Connect to a nearby phone and confirm the pairing code to add it here.")
        paired.forEach { p ->
            val st = status[p.id]
            val reachable = online && st?.reachable == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(p.displayName, active = reachable)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (reachable) LinkColors.Good else LinkColors.Muted, if (reachable) "reachable" else "not reachable")
                        Spacer(Modifier.width(6.dp))
                        Hint(
                            when {
                                !online -> "Offline"
                                st == null || !st.reachable -> "Not in range"
                                st.direct -> "Connected directly"
                                else -> "Via ${st.via?.let { v -> peers.firstOrNull { it.id == v }?.name } ?: "relay"} · ${st.hops} hops"
                            },
                        )
                    }
                }
                IconButton(onClick = { onChat(DeviceId.fromHex(p.id)) }) { Icon(painterResource(R.drawable.ic_chat), contentDescription = "Message ${p.displayName}") }
                IconButton(
                    enabled = reachable,
                    onClick = { gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.call(DeviceId.fromHex(p.id)) } },
                ) { Icon(Icons.Default.Call, contentDescription = "Call ${p.displayName}") }
                var menu by remember { mutableStateOf(false) }
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More for ${p.displayName}") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = p })
                    if (st?.direct == true) DropdownMenuItem(text = { Text("Disconnect") }, onClick = { menu = false; vm.disconnect(DeviceId.fromHex(p.id)) })
                    DropdownMenuItem(text = { Text("Unpair") }, onClick = { menu = false; removing = p })
                }
            }
        }
    }
    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.displayName) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename") },
            text = { OutlinedTextField(text, { text = it.take(40) }, singleLine = true, label = { Text("Name on this phone") }) },
            confirmButton = { TextButton(onClick = { vm.rename(DeviceId.fromHex(p.id), text); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }
    removing?.let { p ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Unpair ${p.displayName}?") },
            text = { Text("Calls and messages with this phone stop until you pair again and compare a new code. Message history is kept.") },
            confirmButton = { TextButton(onClick = { vm.unpair(DeviceId.fromHex(p.id)); removing = null }) { Text("Unpair") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}
