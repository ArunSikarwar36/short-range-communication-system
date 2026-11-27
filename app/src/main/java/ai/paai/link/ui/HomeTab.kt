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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
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
 * Home: the radar over the offline map (or a dark grid), friends as avatar bubbles, one big
 * "Hold to talk" button and colour tiles for SOS, team, team chat and DJ.
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
    var selected by remember { mutableStateOf<RadarDot?>(null) }
    var sosDialog by remember { mutableStateOf(false) }
    var joinIphone by remember { mutableStateOf(false) }
    var groupMuted by remember { mutableStateOf(false) }

    val linked = links.count { it.trusted }
    val region: MapRegion? = remember(regions, me) {
        val m = me
        if (m != null) regions.firstOrNull { it.contains(m.lat, m.lon) } else regions.maxByOrNull { it.sizeBytes }
    }

    // Radar entries (same rules as the radar card): linked, relayed, nearby / pairing.
    val p2pByKey = wifi.peers.filter { it.statusLabel != "Connected" }.associateBy { "p2p:${it.address}" }
    val entries = buildList {
        val geo = friends.associateBy { it.id }
        peers.filter { it.trusted && it.reachable }.forEach {
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

    Box(Modifier.fillMaxSize().background(LinkColors.Background)) {
        if (region != null) MapBackdrop(region, me?.lat, me?.lon) else GridBackdrop()
        // Soft fade so the radar and buttons stay readable over the map.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC0A0F16), Color(0x550A0F16), Color(0xEE0A0F16)))))

        Column(Modifier.fillMaxSize()) {
            // ---------------------------------------------------------------- top bar
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SRCS", fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, color = Color.White)
                Spacer(Modifier.weight(1f))
                StatusPill(online, busy, linked) { if (online) vm.goOffline() else gate.withPermissions(emptyArray(), Reasons.NOTIFY, Perms.notifications) { vm.goOnline() } }
                IconButton(onClick = { onTab("team") }) {
                    val n = invites.size + links.count { !it.trusted }
                    BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) { Icon(Icons.Default.Notifications, "Invitations and pairing", tint = Color.White) }
                }
            }

            // ---------------------------------------------------------------- radar
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 420.dp)
                Box(Modifier.size(side)) {
                    RadarGlow(if (talking) SosRedColor else LinkColors.Accent, active = online)
                    val half = side / 2
                    AvatarBubble(
                        settings.displayName.ifBlank { "You" },
                        Modifier.offset(half - 38.dp, half - 38.dp),
                        sizeDp = 76, ring = if (talking) SosRedColor else Color.White,
                    )
                    dots.forEach { d ->
                        val rad = Math.toRadians(d.angleDeg.toDouble() - 90.0)
                        val x = half + half * (d.radius * cos(rad)).toFloat()
                        val y = half + half * (d.radius * sin(rad)).toFloat()
                        AvatarBubble(
                            d.name,
                            Modifier.offset(x - 42.dp, y - 24.dp).widthIn(max = 84.dp).clickable { selected = d },
                            sizeDp = 46,
                            ring = when (d.ring) { RadarRing.LINKED -> LinkColors.Good; RadarRing.RELAYED -> LinkColors.Accent; RadarRing.NEARBY -> LinkColors.Muted },
                            label = d.name.take(14) + if (d.distanceM >= 0) " · " + Geo.formatDistance(d.distanceM) else "",
                        )
                    }
                    if (!online) {
                        Text("Offline", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), color = LinkColors.Muted)
                    } else if (dots.isEmpty()) {
                        Text("Looking for phones nearby…", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), color = LinkColors.Muted, fontSize = 13.sp)
                    }
                }
            }

            // ---------------------------------------------------------------- bottom panel
            Column(
                Modifier.fillMaxWidth().background(Color(0xF2121A24), RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sos.isNotEmpty()) {
                    val a = sos.first()
                    Row(
                        Modifier.fillMaxWidth().background(SosRedColor.copy(alpha = 0.22f), RoundedCornerShape(14.dp)).clickable { onTab("me") }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("SOS from ${a.name}", color = SosRedColor, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        val f = a.fix
                        val m = me
                        if (f != null && m != null) Text(Geo.formatDistance(Geo.distanceM(m.lat, m.lon, f.lat, f.lon)) + " " + Geo.compass(Geo.bearingDeg(m.lat, m.lon, f.lat, f.lon)), color = Color.White)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val g = group
                    // Mic / mute toggle.
                    Box(
                        Modifier.size(58.dp).background(if (groupMuted) LinkColors.Warn else LinkColors.SurfaceHigh, CircleShape)
                            .clickable(enabled = g != null) { groupMuted = !groupMuted; vm.setGroupMuted(groupMuted) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(painterResource(if (groupMuted) R.drawable.ic_mic_off else R.drawable.ic_mic), "Microphone", tint = Color.White) }
                    val hasMic = vm.c.hasPermission(Manifest.permission.RECORD_AUDIO)
                    val label = when {
                        !online -> "Go online"
                        g == null -> "Start walkie-talkie"
                        g.mode == GroupMode.PTT -> when {
                            talking -> "Talking…"
                            ptt.holder != null -> "${g.members.firstOrNull { it.id == ptt.holder }?.name ?: "Someone"} is talking"
                            else -> "Hold to talk"
                        }
                        else -> "Group call · ${g.members.size}"
                    }
                    val pttMode = online && g != null && g.mode == GroupMode.PTT && hasMic
                    Box(
                        Modifier.weight(1f).height(58.dp)
                            .background(
                                Brush.horizontalGradient(if (talking) listOf(SosRedColor, Color(0xFFFF6B6B)) else listOf(Color(0xFFE11D48), SosRedColor)),
                                RoundedCornerShape(29.dp),
                            )
                            .then(
                                if (pttMode) {
                                    Modifier.pointerInput(Unit) {
                                        detectTapGestures(onPress = {
                                            vm.pressTalk()
                                            tryAwaitRelease()
                                            vm.releaseTalk()
                                        })
                                    }
                                } else {
                                    Modifier.clickable {
                                        when {
                                            !online -> gate.withPermissions(emptyArray(), Reasons.NOTIFY, Perms.notifications) { vm.goOnline() }
                                            g == null -> {
                                                val reachable = peers.filter { it.trusted && it.reachable }.map { it.id }
                                                if (reachable.isEmpty()) { vm.notify("Pair with a phone nearby first"); onTab("team") }
                                                else gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.createGroup("Walkie-talkie", GroupMode.PTT, reachable) }
                                            }
                                            !hasMic -> gate.withPermissions(Perms.microphone, Reasons.MIC) {}
                                            else -> onTab("team")
                                        }
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionTile("Team SOS", SosRedColor, Modifier.weight(1f)) { sosDialog = true }
                    ActionTile(if (group == null) "Create team" else "Team · ${group!!.members.size}", TeamTeal, Modifier.weight(1f)) { onTab("team") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionTile("Team chat", ChatAmber, Modifier.weight(1f), dark = true) {
                        val g = group
                        if (g != null) onNavigate("chat/${g.groupId.hex}") else onTab("chats")
                    }
                    ActionTile("DJ Mode", DjPurple, Modifier.weight(1f)) { onTab("team") }
                }
                // Connection strip.
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF0E151E), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).background(if (linked > 0) LinkColors.Good else if (online) LinkColors.Warn else LinkColors.Muted, CircleShape))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !online -> "Offline"
                            wifi.group.formed && wifi.group.isOwner -> "Wi-Fi Direct · hosting · ${wifi.group.clientCount} joined"
                            wifi.group.formed -> "Wi-Fi Direct · joined a host"
                            wifi.discovering -> "Scanning nearby…"
                            else -> "Wi-Fi Direct · $linked linked"
                        },
                        color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f),
                    )
                    if (online && wifi.group.isOwner && wifi.group.passphrase != null) {
                        TextButton(onClick = { joinIphone = true }) { Text("Add iPhone", fontSize = 13.sp) }
                    }
                    if (online && wifi.supported) {
                        IconButton(onClick = { gate.withPermissions(Perms.discovery, Reasons.DISCOVERY) { vm.scan() } }) {
                            if (wifi.discovering) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Refresh, "Scan for nearby phones", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (sosDialog) SosDialog(vm) { sosDialog = false }
    val joinName = wifi.group.networkName
    val joinPass = wifi.group.passphrase
    if (joinIphone && joinName != null && joinPass != null) JoinIphoneDialog(joinName, joinPass) { joinIphone = false }
    selected?.let { d ->
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

@Composable
private fun StatusPill(online: Boolean, busy: Boolean, linked: Int, onClick: () -> Unit) {
    Row(
        Modifier.background(Color(0xCC121A24), RoundedCornerShape(20.dp)).border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(9.dp).background(if (!online) LinkColors.Muted else if (linked > 0) LinkColors.Good else LinkColors.Warn, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (!online) "Offline · tap to go online" else if (linked > 0) "Online · $linked" else "Online", color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun ActionTile(label: String, color: Color, modifier: Modifier, dark: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier.height(64.dp)
            .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.78f))), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (dark) Color(0xFF1A1300) else Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
}

/** Expanding glow rings around this phone (red while transmitting). */
@Composable
private fun RadarGlow(color: Color, active: Boolean) {
    val t = rememberInfiniteTransition(label = "glow")
    val p1 by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p1")
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0.12f), Color.Transparent), c, r * 0.75f), r * 0.75f, c)
        for (ring in RadarRing.entries) drawCircle(color.copy(alpha = 0.28f), r * ring.radius, c, style = Stroke(1.5.dp.toPx()))
        if (active) {
            for (k in 0..1) {
                val p = (p1 + k * 0.5f) % 1f
                drawCircle(color.copy(alpha = (1 - p) * 0.55f), r * (0.2f + 0.8f * p), c, style = Stroke(3.dp.toPx()))
            }
        }
    }
}

/** Dark grid when no offline map is available. */
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
