package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.core.group.GroupRole
import ai.paai.link.core.group.GroupStatus
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.util.DeviceId
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreen(vm: LinkViewModel, gate: PermissionGate, onBack: (() -> Unit)?, onChat: (DeviceId) -> Unit, header: (@Composable () -> Unit)? = null) {
    val group by vm.group.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        // In the Team tab the header draws its own title.
        if (onBack != null) TopAppBar(
            title = { Text(group?.name ?: "Group & walkie-talkie", fontWeight = FontWeight.Bold) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        if (group == null) CreateGroup(vm, gate, header) else ActiveGroup(vm, onChat, header)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateGroup(vm: LinkViewModel, gate: PermissionGate, header: (@Composable () -> Unit)?) {
    val online by vm.online.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(GroupMode.PTT) }
    val selected = remember { mutableStateMapOf<DeviceId, Boolean>() }
    val candidates = peers.filter { it.trusted && it.reachable }
    val chosen = selected.filterValues { it }.keys.toList()

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (header != null) item { header() }
        item {
            SectionCard("New group") {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(mode == GroupMode.PTT, { mode = GroupMode.PTT }, SegmentedButtonDefaults.itemShape(0, 2)) { Text("Walkie-talkie") }
                    SegmentedButton(mode == GroupMode.CALL, { mode = GroupMode.CALL }, SegmentedButtonDefaults.itemShape(1, 2)) { Text("Group call") }
                }
                Hint(
                    if (mode == GroupMode.PTT) "Hold the button to talk; one person speaks at a time."
                    else "Everyone can talk at once. Best for small groups.",
                )
                Hint("This phone hosts the group and relays audio between members (up to ${settings.maxGroupMembers} including you).")
            }
        }
        item {
            SectionCard("Invite") {
                if (!online) Hint("Go online first.")
                else if (candidates.isEmpty()) Hint("No paired devices are in range. Pair and connect phones on the home screen first.")
                candidates.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(
                            value = selected[p.id] == true,
                            role = Role.Checkbox,
                            onValueChange = { on -> if (!on || chosen.size < settings.maxGroupMembers - 1) selected[p.id] = on },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected[p.id] == true, onCheckedChange = null)
                        Text(p.name, Modifier.weight(1f))
                        Hint(if (p.direct) "direct" else "${p.hops} hops")
                    }
                }
                Button(
                    enabled = online && chosen.isNotEmpty(),
                    onClick = {
                        gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) {
                            vm.createGroup(name.ifBlank { if (mode == GroupMode.PTT) "Channel" else "Group call" }, mode, chosen)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Start ${if (mode == GroupMode.PTT) "walkie-talkie" else "group call"}") }
            }
        }
    }
}

@Composable
private fun ActiveGroup(vm: LinkViewModel, onChat: (DeviceId) -> Unit, header: (@Composable () -> Unit)?) {
    val group by vm.group.collectAsStateWithLifecycle()
    val ptt by vm.ptt.collectAsStateWithLifecycle()
    val session by vm.groupSession.collectAsStateWithLifecycle()
    val route by vm.route.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val stack by vm.stack.collectAsStateWithLifecycle()
    val g = group ?: return
    val selfId = stack?.node?.selfId
    var levels by remember { mutableStateOf<Map<DeviceId, Float>>(emptyMap()) }
    var localLevel by remember { mutableFloatStateOf(0f) }
    var muted by remember { mutableStateOf(false) }
    val memberMuted = remember { mutableStateMapOf<DeviceId, Boolean>() }
    var inviting by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        while (true) {
            session?.let { it.refresh(); levels = it.levels.value; localLevel = it.localLevel }
            delay(250)
        }
    }

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (header != null) item { header() }
        item {
            SectionCard(if (g.mode == GroupMode.PTT) "Walkie-talkie" else "Group call") {
                StatusLine(
                    when (g.status) { GroupStatus.ACTIVE -> LinkColors.Good; GroupStatus.JOINING -> LinkColors.Warn; GroupStatus.RECOVERING -> LinkColors.Warn },
                    when (g.status) {
                        GroupStatus.JOINING -> "Joining…"
                        GroupStatus.RECOVERING -> "Host lost — recovering…"
                        GroupStatus.ACTIVE -> if (g.role == GroupRole.HOST) "You are hosting" else "Hosted by ${g.members.firstOrNull()?.name}"
                    },
                    "${g.members.size} members · end-to-end encrypted · new key on every member change",
                )
                if (route.available.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    route.available.forEach { r -> FilterChip(route.current == r, { vm.selectRoute(r) }, label = { Text(r.label) }) }
                }
            }
        }
        if (g.mode == GroupMode.PTT && g.status == GroupStatus.ACTIVE) item {
            val holderName = ptt.holder?.let { h -> g.members.firstOrNull { it.id == h }?.name ?: "someone" }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    when {
                        ptt.talking -> "You are talking"
                        ptt.requesting -> "Requesting channel…"
                        ptt.holder != null -> "$holderName is talking"
                        ptt.revoked -> "Talk time ended"
                        ptt.denied -> "Channel busy"
                        else -> "Channel free"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (ptt.holder != null && !ptt.talking) LinkColors.Warn else LinkColors.OnSurface,
                )
                Spacer(Modifier.height(16.dp))
                TalkButton(talking = ptt.talking, busy = ptt.holder != null && !ptt.talking, level = localLevel, onPress = vm::pressTalk, onRelease = vm::releaseTalk)
                Spacer(Modifier.height(8.dp))
                Hint("Hold to talk · release to listen")
            }
        }
        if (g.status == GroupStatus.ACTIVE) item { DjCard(vm, isHost = g.role == ai.paai.link.core.group.GroupRole.HOST) }
        if (g.mode == GroupMode.CALL) item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { muted = !muted; vm.setGroupMuted(muted) }) {
                    Icon(painterResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic), null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (muted) "Unmute" else "Mute")
                }
            }
        }
        item {
            SectionCard("Members", action = {
                if (g.role == GroupRole.HOST) TextButton(onClick = { inviting = !inviting }) { Text(if (inviting) "Done" else "Invite") }
            }) {
                g.members.forEach { m ->
                    val speaking = (if (m.id == selfId) localLevel else levels[m.id] ?: 0f) > 0.02f
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(m.name, active = speaking || ptt.holder == m.id)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.name + if (m.id == selfId) " (you)" else "")
                            Hint(listOfNotNull(if (m.id == g.members.first().id) "host" else null, if (speaking) "speaking" else null).joinToString(" · "))
                        }
                        if (m.id != selfId) {
                            val mm = memberMuted[m.id] == true
                            TextButton(onClick = { memberMuted[m.id] = !mm; vm.setMemberMuted(m.id, !mm) }) { Text(if (mm) "Unmute" else "Mute") }
                            if (g.role == GroupRole.HOST) TextButton(onClick = { vm.kick(m.id) }) { Text("Remove", color = LinkColors.Bad) }
                        }
                    }
                }
                if (g.invited.isNotEmpty()) Hint("Invited, not yet joined: ${g.invited.joinToString { id -> peers.firstOrNull { it.id == id }?.name ?: id.short }}")
                if (inviting) {
                    val candidates = peers.filter { p -> p.trusted && p.reachable && g.members.none { it.id == p.id } && p.id !in g.invited }
                    if (candidates.isEmpty()) Hint("No other paired devices in range.")
                    candidates.forEach { p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(p.name, Modifier.weight(1f))
                            TextButton(onClick = { vm.inviteToGroup(p.id) }) { Text("Invite") }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onChat(g.groupId) }) { Text("Group chat") }
                Button(onClick = vm::leaveGroup) { Text(if (g.role == GroupRole.HOST) "End group" else "Leave") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Press-and-hold talk control. Pointer cancellation (e.g. a system gesture or the app losing
 * focus) releases the floor exactly like lifting the finger.
 */
@Composable
private fun TalkButton(talking: Boolean, busy: Boolean, level: Float, onPress: () -> Unit, onRelease: () -> Unit) {
    val color = when {
        talking -> Color(0xFFFF1F2D)
        busy -> LinkColors.SurfaceHigh
        else -> Ui.Red
    }
    Box(
        Modifier
            .size(180.dp + (level * 40).dp)
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.08f))), CircleShape)
            .padding(16.dp)
            .shadow(if (busy) 0.dp else 24.dp, CircleShape, ambientColor = color, spotColor = color)
            .background(androidx.compose.ui.graphics.Brush.linearGradient(if (busy) listOf(color, color) else listOf(Ui.RedLight, color)), CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = "Push to talk"
                stateDescription = if (talking) "Transmitting" else if (busy) "Channel busy" else "Ready"
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    onPress()
                    waitForUpOrCancellation()
                    onRelease()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
            Text(if (talking) "TALKING" else "HOLD", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
