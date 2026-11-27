package ai.paai.link.ui

import ai.paai.link.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The app's main screen: five tabs (Home, Team, Map, Chats, Me) with a bottom bar. */
@Composable
fun MainShell(vm: LinkViewModel, gate: PermissionGate, onNavigate: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOfTab() }
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val invites by vm.invites.collectAsStateWithLifecycle()
    val unread = conversations.sumOf { it.unread }

    Column(Modifier.fillMaxSize().background(LinkColors.Background)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                "home" -> HomeTab(vm, gate, onTab = { tab = it }, onNavigate = onNavigate)
                "team" -> TeamTab(vm, gate, onChat = { onNavigate("chat/${it.hex}") })
                "map" -> MapScreen(vm, null, null, onBack = null, onManage = { onNavigate("mapareas") })
                "chats" -> ConversationsScreen(vm, onOpen = { onNavigate("chat/${it.hex}") }, onBack = null)
                else -> MeTab(vm, gate, onTab = { tab = it }, onNavigate = onNavigate)
            }
        }
        NavigationBar(containerColor = Color(0xFF0B1118), tonalElevation = 0.dp) {
            @Composable
            fun item(id: String, label: String, icon: Painter, badge: Int = 0) = NavigationBarItem(
                selected = tab == id,
                onClick = { tab = id },
                icon = { BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, label) } },
                label = { Text(label, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Color(0xFF1B2A3A),
                    unselectedIconColor = LinkColors.Muted, unselectedTextColor = LinkColors.Muted,
                ),
            )
            item("home", "Home", rememberVectorPainter(Icons.Default.Home))
            item("team", "Team", painterResource(R.drawable.ic_group), invites.size)
            item("map", "Map", rememberVectorPainter(Icons.Default.Place))
            item("chats", "Chats", painterResource(R.drawable.ic_chat), unread)
            item("me", "Me", rememberVectorPainter(Icons.Default.Person))
        }
    }
}

private fun mutableStateOfTab() = androidx.compose.runtime.mutableStateOf("home")

/** Me: profile, the feature grid, location & SOS, and shortcuts to the detail screens. */
@Composable
fun MeTab(vm: LinkViewModel, gate: PermissionGate, onTab: (String) -> Unit, onNavigate: (String) -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val fingerprint by vm.fingerprint.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val showOnMap: (Double, Double, String) -> Unit = { lat, lon, label ->
        if (vm.hasMapFor(lat, lon)) onNavigate("map?lat=$lat&lon=$lon") else openInMapApp(context, lat, lon, label)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarBubble(settings.displayName.ifBlank { "Me" }, sizeDp = 68, ring = if (online) LinkColors.Good else LinkColors.Muted)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(settings.displayName.ifBlank { "This phone" }, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("SRCS · offline communication network", color = LinkColors.Muted, fontSize = 13.sp)
                    fingerprint?.let { Text("Key $it", color = LinkColors.Muted, fontSize = 11.sp) }
                }
            }
        }
        item {
            Text("FEATURES", color = LinkColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(8.dp))
            val tiles = listOf(
                Feature("Voice calling", "Private calls", rememberVectorPainter(Icons.Default.Call), LinkColors.Accent) { onTab("team") },
                Feature("Walkie-talkie", "Hold to talk", painterResource(R.drawable.ic_mic), SosRedColor) { onTab("home") },
                Feature("Offline maps", "No internet needed", rememberVectorPainter(Icons.Default.Place), TeamTeal) { onTab("map") },
                Feature("Live location", "Share with your team", rememberVectorPainter(Icons.Default.LocationOn), LinkColors.Good) { },
                Feature("Route sharing", "Coming soon", rememberVectorPainter(Icons.Default.Share), LinkColors.Muted) { vm.notify("Route sharing is coming in a later version") },
                Feature("DJ Mode", "Music for the team", rememberVectorPainter(Icons.Default.PlayArrow), DjPurple) { onTab("team") },
                Feature("Offline chat", "Messages & receipts", painterResource(R.drawable.ic_chat), ChatAmber) { onTab("chats") },
                Feature("Device relay", "Extend the range", painterResource(R.drawable.ic_stat_link), Color(0xFF60A5FA)) { onNavigate("settings") },
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { f -> FeatureTile(f, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (online) item { SosAlerts(vm, showOnMap) }
        if (online) item { LocationCard(vm, gate, showOnMap) }
        item {
            Column(Modifier.fillMaxWidth().background(Color(0xFF121A24), RoundedCornerShape(20.dp))) {
                MenuRow("Map areas", "Download or import offline maps") { onNavigate("mapareas") }
                MenuRow("Devices & connections", "Paired phones, rename, unpair, Wi-Fi Direct") { onNavigate("devices") }
                MenuRow("Settings", "Name, relaying, Wi-Fi Aware, groups") { onNavigate("settings") }
                MenuRow("Diagnostics", "Links, routes, radios and the log") { onNavigate("diagnostics") }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private class Feature(val title: String, val sub: String, val icon: Painter, val color: Color, val onClick: () -> Unit)

@Composable
private fun FeatureTile(f: Feature, modifier: Modifier) {
    Column(
        modifier.background(Color(0xFF121A24), RoundedCornerShape(18.dp)).border(1.dp, f.color.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
            .clickable(onClick = f.onClick).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(38.dp).background(f.color.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(f.icon, null, tint = f.color, modifier = Modifier.size(22.dp))
        }
        Text(f.title, color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(f.sub, color = LinkColors.Muted, fontSize = 12.sp)
    }
}

@Composable
private fun MenuRow(title: String, sub: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(sub, color = LinkColors.Muted, fontSize = 12.sp)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = LinkColors.Muted)
    }
}
