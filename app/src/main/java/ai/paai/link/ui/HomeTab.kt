package ai.paai.link.ui

import ai.paai.link.R
import ai.paai.link.core.app.RadarDot
import ai.paai.link.core.app.RadarEntry
import ai.paai.link.core.app.RadarLayout
import ai.paai.link.core.app.RadarRing
import ai.paai.link.core.location.Geo
import ai.paai.link.core.maps.MapRegion
import ai.paai.link.core.protocol.GroupMode
import ai.paai.link.core.util.DeviceId
import android.Manifest
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.cos
import kotlin.math.sin

/**
 * Home: the radar over the offline map (or a dark grid) with friends as avatar bubbles, one glossy
 * main button and small round buttons for SOS, team chat and DJ. With nobody nearby it turns
 * into a "scan for phones" screen.
 */
@Composable
fun HomeTab(vm: LinkViewModel, gate: PermissionGate, onTab: (String) -> Unit, onNavigate: (String) -> Unit) {
    val online by vm.online.collectAsStateWithLifecycle()
    val busy by vm.transitioning.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val wifi by vm.wifi.collectAsStateWithLifecycle()
    val aware by vm.aware.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val ptt by vm.ptt.collectAsStateWithLifecycle()
    val friends by vm.friendLocations.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    val sos by vm.activeSos.collectAsStateWithLifecycle()
    val regions by vm.c.maps.list.collectAsStateWithLifecycle()
    val autoStatus by vm.autoConnectStatus.collectAsStateWithLifecycle()
    var confirmDisconnect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<RadarDot?>(null) }
    var sosDialog by remember { mutableStateOf(false) }
    var joinIphone by remember { mutableStateOf(false) }
    var groupMuted by remember { mutableStateOf(false) }

    val linked = links.count { it.trusted }
    val reachable = peers.filter { it.trusted && it.reachable }
    val region: MapRegion? = remember(regions, me) {
        val m = me
        if (m != null) regions.firstOrNull { it.contains(m.lat, m.lon) } else regions.maxByOrNull { it.sizeBytes }
    }

    // Radar entries: linked, relayed, nearby / pairing.
    val p2pByKey = wifi.peers.filter { it.statusLabel != "Connected" }.associateBy { "p2p:${it.address}" }
    val entries = buildList {
        val geo = friends.associateBy { it.id }
        reachable.forEach {
            val f = geo[it.id]
            val my = me
            add(
                RadarEntry(
                    it.id.hex, it.name, if (it.direct) RadarRing.LINKED else RadarRing.RELAYED, peerId = it.id.hex,
                    bearingDeg = if (f != null && my != null) Geo.bearingDeg(my.lat, my.lon, f.lat, f.lon) else null,
                    distanceM = if (f != null && my != null) Geo.distanceM(my.lat, my.lon, f.lat, f.lon) else null,
                ),
            )
        }
        links.filter { !it.trusted }.forEach { add(RadarEntry("link:${it.linkId}", it.remoteName, RadarRing.NEARBY, pairingLinkId = it.linkId)) }
        p2pByKey.forEach { (k, p) -> add(RadarEntry(k, p.name, RadarRing.NEARBY)) }
        aware.peers.filter { !it.linked }.forEach { add(RadarEntry("aware:${it.key}", "SRCS phone", RadarRing.NEARBY)) }
    }
    val dots = remember(entries) { RadarLayout.place(entries) }
    val talking = ptt.talking
    // "Connected" layout once a paired phone is reachable or a team is running; otherwise the scan layout.
    val connected = online && (reachable.isNotEmpty() || group != null)
    val goOnline = { gate.withPermissions(emptyArray(), Reasons.NOTIFY, Perms.notifications) { vm.goOnline() } }
    val scan = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.scan() } }

    Box(Modifier.fillMaxSize().background(LinkColors.Background)) {
        if (region != null) MapBackdrop(region, me?.lat, me?.lon) else GridBackdrop()
        // Dark at the top and bottom so text and buttons stay readable over the map.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color(0xE005070A), 0.22f to Color(0x2005070A), 0.55f to Color(0x2005070A), 0.78f to Color(0xF005070A), 1f to Color(0xFA05070A),
                ),
            ),
        )

        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- top bar
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SRCS", fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, color = Ui.Text)
                Spacer(Modifier.weight(1f))
                StatusChip(
                    when {
                        busy -> if (online) "Going offline…" else "Starting…"
                        !online -> "Offline"
                        reachable.isNotEmpty() -> "Online · ${reachable.size} nearby"
                        wifi.discovering -> "Online · searching"
                        else -> "Online"
                    },
                    when { !online -> LinkColors.Muted; reachable.isNotEmpty() -> LinkColors.Good; else -> LinkColors.Warn },
                    onClick = { if (online) vm.goOffline() else goOnline() },
                )
                Spacer(Modifier.width(10.dp))
                val n = invites.size + links.count { !it.trusted }
                Box(
                    Modifier.size(40.dp).glass(CircleShape).clickable { onTab("team") },
                    contentAlignment = Alignment.Center,
                ) {
                    BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) {
                        Icon(Icons.Default.Notifications, "Invitations and pairing", tint = Ui.Text, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Column(Modifier.padding(horizontal = 20.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                UpdateBanner(vm)
                invites.forEach { inv ->
                    val ptt = inv.mode == GroupMode.PTT
                    Column(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), TeamTeal.copy(alpha = 0.18f), TeamTeal.copy(alpha = 0.5f)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            if (ptt) "${inv.fromName} started a walkie-talkie" else "${inv.fromName} invited you to a group call",
                            color = Ui.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            PrimaryPill("Join", Modifier.weight(1f), height = 44.dp, colors = listOf(TeamTeal, Color(0xFF0EA5A0))) {
                                gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.acceptInvite(inv.groupId) }
                            }
                            GlassButton("Not now", Modifier.weight(1f)) { vm.declineInvite(inv.groupId) }
                        }
                    }
                }
                sos.firstOrNull()?.let { a ->
                    Row(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), Ui.Red.copy(alpha = 0.2f), Ui.Red.copy(alpha = 0.5f))
                            .clickable { onTab("me") }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("SOS from ${a.name}", color = Color(0xFFFF8A90), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        val f = a.fix
                        val m = me
                        if (f != null && m != null) Text(Geo.formatDistance(Geo.distanceM(m.lat, m.lon, f.lat, f.lon)) + " " + Geo.compass(Geo.bearingDeg(m.lat, m.lon, f.lat, f.lon)), color = Color.White)
                    }
                }
            }

            // ---------------------------------------------------------------- radar
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth - 24.dp, maxHeight, 420.dp)
                Box(Modifier.size(side)) {
                    RadarGlow(if (talking) Ui.Red else Color(0xFFFF3C46), active = online, sweep = online && dots.isEmpty())
                    val half = side / 2
                    val meSize = if (dots.isEmpty()) 76 else 84
                    AvatarBubble(
                        settings.displayName.ifBlank { "You" },
                        Modifier.offset(half - (meSize / 2).dp, half - (meSize / 2).dp)
                            .shadow(24.dp, CircleShape, ambientColor = Ui.Red, spotColor = Ui.Red),
                        sizeDp = meSize, ring = Color.White,
                    )
                    dots.forEach { d ->
                        val rad = Math.toRadians(d.angleDeg.toDouble() - 90.0)
                        val x = half + half * (d.radius * cos(rad)).toFloat()
                        val y = half + half * (d.radius * sin(rad)).toFloat()
                        AvatarBubble(
                            d.name,
                            Modifier.offset(x - 42.dp, y - 26.dp).widthIn(max = 84.dp).clickable { selected = d },
                            sizeDp = 52,
                            ring = when (d.ring) { RadarRing.LINKED -> LinkColors.Good; RadarRing.RELAYED -> LinkColors.Accent; RadarRing.NEARBY -> Color(0x99FFFFFF) },
                            label = d.name.take(12) + when {
                                d.distanceM >= 0 -> " · " + Geo.formatDistance(d.distanceM)
                                d.ring == RadarRing.RELAYED -> " · relay"
                                d.ring == RadarRing.NEARBY -> " · new"
                                else -> ""
                            },
                        )
                    }
                }
                // Small round buttons on the right: SOS always; chat and DJ once someone is connected.
                if (online) {
                    Column(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier.size(52.dp).glass(RoundedCornerShape(18.dp), Ui.Red.copy(alpha = 0.16f), Ui.Red.copy(alpha = 0.5f)).clickable { sosDialog = true },
                            contentAlignment = Alignment.Center,
                        ) { Text("SOS", color = Color(0xFFFF6B72), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
                        if (connected) {
                            RoundIcon(painterResource(R.drawable.ic_chat), "Team chat", ChatAmber, shape = RoundedCornerShape(18.dp)) {
                                val g = group
                                if (g != null) onNavigate("chat/${g.groupId.hex}") else onTab("chats")
                            }
                            RoundIcon(painterResource(R.drawable.ic_music), "DJ Mode", Ui.Purple, shape = RoundedCornerShape(18.dp)) { onTab("team") }
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- bottom
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!connected) {
                    // Empty state: nobody to talk to yet.
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            when {
                                !online -> "You're offline"
                                autoStatus != null -> "Looking for your phones"
                                dots.isNotEmpty() -> "Phones found nearby"
                                else -> "No one nearby yet"
                            },
                            color = Ui.Text, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            when {
                                !online -> "Go online to find phones nearby. Calls and messages never use the internet."
                                autoStatus != null -> autoStatus!!.removeSuffix("…") + "… Phones you connected to before join by themselves when they are nearby and SRCS is online."
                                dots.isNotEmpty() -> "Tap a phone on the radar to connect, then compare the pairing code on both phones."
                                wifi.discovering -> "Searching… Open SRCS on your friend's phone and tap Scan there too."
                                else -> "Open SRCS on your friend's phone and tap Scan there too. Keep the phones within about 50 m."
                            },
                            color = Ui.Sub, fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                        )
                    }
                    if (!online) {
                        PrimaryPill(if (busy) "Starting…" else "Go online", Modifier.fillMaxWidth(), painterResource(R.drawable.ic_radar), enabled = !busy) { goOnline() }
                    } else {
                        PrimaryPill(if (wifi.discovering) "Scanning…" else "Scan for phones", Modifier.fillMaxWidth(), painterResource(R.drawable.ic_radar)) { scan() }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            when {
                                wifi.group.isOwner && wifi.group.passphrase != null -> GlassButton("Add an iPhone", Modifier.weight(1f)) { joinIphone = true }
                                wifi.supported && !wifi.group.formed -> GlassButton("Host a group", Modifier.weight(1f)) {
                                    gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.hostGroup() }
                                }
                                else -> GlassButton("Leave Wi-Fi group", Modifier.weight(1f)) { vm.leaveP2p() }
                            }
                            GlassButton("Devices", Modifier.weight(1f)) { onNavigate("devices") }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val g = group
                        RoundIcon(
                            painterResource(if (groupMuted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                            if (groupMuted) "Microphone off" else "Microphone on",
                            if (groupMuted) LinkColors.Warn else Ui.Text, size = 60.dp,
                            background = Color(0xE61E2632),
                        ) { if (g != null) { groupMuted = !groupMuted; vm.setGroupMuted(groupMuted) } else vm.notify("Start a walkie-talkie team first") }
                        val hasMic = vm.c.hasPermission(Manifest.permission.RECORD_AUDIO)
                        val label = when {
                            g == null -> "Start walkie-talkie"
                            g.mode == GroupMode.PTT -> when {
                                talking -> "Talking…"
                                ptt.holder != null -> "${g.members.firstOrNull { it.id == ptt.holder }?.name ?: "Someone"} is talking"
                                else -> "Hold to talk"
                            }
                            else -> "Group call · ${g.members.size}"
                        }
                        val pttMode = g != null && g.mode == GroupMode.PTT && hasMic
                        PrimaryPill(
                            label,
                            Modifier.weight(1f).then(
                                if (pttMode) {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(onPress = {
                                            vm.pressTalk()
                                            tryAwaitRelease()
                                            vm.releaseTalk()
                                        })
                                    }
                                } else Modifier,
                            ),
                            height = 60.dp,
                            colors = if (talking) listOf(Color(0xFFFF1F2D), Ui.Red) else listOf(Ui.Red, Ui.RedLight),
                            onClick = if (pttMode) null else ({
                                when {
                                    g == null -> gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) {
                                        vm.createGroup("Walkie-talkie", GroupMode.PTT, reachable.map { it.id })
                                    }
                                    !hasMic -> gate.withPermissions(Perms.microphone, Reasons.MIC) {}
                                    else -> onTab("team")
                                }
                            }),
                        )
                    }
                    // Channel strip.
                    Row(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), Color(0xD910161E), Color(0x14FFFFFF))
                            .clickable { onTab("team") }.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(30.dp).background(LinkColors.Good.copy(alpha = 0.15f), CircleShape), contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.ic_radar), null, tint = LinkColors.Good, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            val g = group
                            Text(
                                if (g != null) "${g.name} · ${if (g.mode == GroupMode.PTT) "walkie-talkie" else "group call"}" else "${reachable.size} phone${if (reachable.size == 1) "" else "s"} connected",
                                color = Ui.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                            )
                            val waiting = g != null && g.members.size == 1 && g.invited.isNotEmpty()
                            Text(
                                when {
                                    waiting -> "Waiting for ${g!!.invited.mapNotNull { id -> peers.firstOrNull { it.id == id }?.name }.joinToString().ifBlank { "the others" }} to join…"
                                    g != null -> g.members.joinToString { it.name }
                                    else -> reachable.joinToString { it.name } + " · tap Start walkie-talkie"
                                },
                                color = if (waiting) LinkColors.Warn else Ui.Sub, fontSize = 12.sp, maxLines = 1,
                            )
                        }
                        Text(
                            "Disconnect", color = Color(0xFFFF8A90), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.background(Ui.Red.copy(alpha = 0.14f), RoundedCornerShape(50)).clickable { confirmDisconnect = true }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }
        }
    }

    if (sosDialog) SosDialog(vm) { sosDialog = false }
    if (confirmDisconnect) AlertDialog(
        onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect?") },
        text = { Text("Ends the walkie-talkie and the Wi-Fi link with ${reachable.joinToString { it.name }.ifBlank { "the other phones" }}. They are told, so nobody has to disconnect twice. Tap Scan to connect again later.") },
        confirmButton = { TextButton(onClick = { confirmDisconnect = false; vm.disconnectAll() }) { Text("Disconnect", color = LinkColors.Bad) } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } },
    )
    val joinName = wifi.group.networkName
    val joinPass = wifi.group.passphrase
    if (joinIphone && joinName != null && joinPass != null) JoinIphoneDialog(joinName, joinPass) { joinIphone = false }
    val sel = selected
    if (sel != null && sel.peerId.isNotEmpty()) {
        PeerSheet(vm, gate, DeviceId.fromHex(sel.peerId), onDismiss = { selected = null }, onNavigate = onNavigate, onTab = onTab)
    } else selected?.let { d ->
        val p2p = p2pByKey[d.key]
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(d.name) },
            text = {
                Text(
                    when {
                        d.distanceM >= 0 -> "${Geo.formatDistance(d.distanceM)} away (GPS). " + if (d.ring == RadarRing.LINKED) "Linked directly." else "Reached through a relay."
                        d.peerId.isNotEmpty() && d.ring == RadarRing.LINKED -> "Linked directly."
                        d.peerId.isNotEmpty() -> "Reached through another phone (relay)."
                        d.pairingLinkId >= 0 -> "Waiting for both phones to confirm the pairing code."
                        p2p != null -> "Nearby over Wi-Fi Direct. Connect to link and pair."
                        else -> "Nearby. SRCS links automatically and then asks you to compare a pairing code."
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
                if (d.peerId.isNotEmpty()) TextButton(onClick = { selected = null; onNavigate("chat/${d.peerId}") }) { Text("Message") }
            },
        )
    }
}

/** Soft red glow, faint range rings, expanding pulses and (while searching) a rotating sweep. */
@Composable
private fun RadarGlow(color: Color, active: Boolean, sweep: Boolean) {
    val t = rememberInfiniteTransition(label = "glow")
    val p1 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing), RepeatMode.Restart), label = "p1")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(
            Brush.radialGradient(
                0f to color.copy(alpha = if (active) 0.55f else 0.25f), 0.38f to color.copy(alpha = if (active) 0.22f else 0.1f),
                0.62f to color.copy(alpha = 0.06f), 0.75f to Color.Transparent, center = c, radius = r,
            ),
            r, c,
        )
        for (ring in RadarRing.entries) drawCircle(Color(0x40FF7878), r * ring.radius, c, style = Stroke(1.dp.toPx()))
        if (active) {
            for (k in 0..1) {
                val p = (p1 + k * 0.5f) % 1f
                drawCircle(color.copy(alpha = (1 - p) * 0.6f), r * (0.3f + 0.7f * p), c, style = Stroke(2.dp.toPx()))
            }
        }
        if (sweep) {
            rotate(angle, c) {
                drawCircle(
                    Brush.sweepGradient(0f to Color.Transparent, 0.75f to Color.Transparent, 1f to color.copy(alpha = 0.45f), center = c),
                    r * 0.92f, c,
                )
            }
        }
    }
}

@Composable
private fun GridBackdrop() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1622), Color(0xFF0A0F16))))
        val step = 48.dp.toPx()
        var x = 0f
        while (x < size.width) { drawLine(Color(0x1422C7E6), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
        var y = 0f
        while (y < size.height) { drawLine(Color(0x1422C7E6), Offset(0f, y), Offset(size.width, y), 1f); y += step }
    }
}

/** The offline map under the radar (no gestures; centred on this phone). */
@Composable
private fun MapBackdrop(region: MapRegion, lat: Double?, lon: Double?) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mapView = remember { MapLibre.getInstance(context); MapView(context).apply { onCreate(null) } }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
    }
    AndroidView({ mapView }, Modifier.fillMaxSize())
    LaunchedEffect(region.path) {
        mapView.getMapAsync { m ->
            m.uiSettings.setAllGesturesEnabled(false)
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.isLogoEnabled = false
            m.setStyle(Style.Builder().fromJson(styleFor(context, region))) {
                val la = lat?.takeIf { region.contains(it, lon ?: 0.0) } ?: region.centerLat
                val lo = lon?.takeIf { region.contains(lat ?: 0.0, it) } ?: region.centerLon
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), minOf(15.0, region.maxZoom.toDouble())))
            }
        }
    }
    LaunchedEffect(lat, lon) {
        if (lat != null && lon != null && region.contains(lat, lon)) mapView.getMapAsync { it.moveCamera(CameraUpdateFactory.newLatLng(LatLng(lat, lon))) }
    }
}
