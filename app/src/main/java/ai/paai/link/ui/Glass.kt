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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Colours of the 0.5 look: dark map, red glow, glass panels, purple for the team side. */
object Ui {
    val Red = Color(0xFFFF3B47)
    val RedLight = Color(0xFFFF6A5F)
    val Purple = Color(0xFFC4A0FF)
    val PurpleDeep = Color(0xFF2A1240)
    val Pink = Color(0xFFEC4899)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0xCC10161E)
    val Line = Color(0x1FFFFFFF)
    val Text = Color(0xFFF4F7FB)
    val Sub = Color(0xFF9AA6B2)
    val Bar = Color(0xFF0B1016)
}

/** Translucent panel with a thin light border. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(24.dp), color: Color = Ui.Glass, line: Color = Ui.Line): Modifier =
    this.background(color, shape).border(1.dp, line, shape)

/** The one big glossy button of a screen (red gradient with a soft glow). */
@Composable
fun PrimaryPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    height: Dp = 58.dp,
    colors: List<Color> = listOf(Ui.Red, Ui.RedLight),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(height / 2)
    Row(
        modifier
            .height(height)
            .shadow(if (enabled) 18.dp else 0.dp, shape, ambientColor = colors.first(), spotColor = colors.first())
            .background(Brush.horizontalGradient(if (enabled) colors else listOf(Color(0xFF2A3340), Color(0xFF2A3340))), shape)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (height >= 56.dp) 20.sp else 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Secondary glass button. */
@Composable
fun GlassButton(text: String, modifier: Modifier = Modifier, icon: Painter? = null, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).glass(RoundedCornerShape(22.dp), Ui.GlassStrong).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Ui.Text, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = Ui.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
    }
}

/** Small round (or rounded-square) icon button. */
@Composable
fun RoundIcon(
    icon: Painter,
    description: String,
    tint: Color = Ui.Text,
    modifier: Modifier = Modifier,
    size: Dp = 52.dp,
    shape: Shape = CircleShape,
    background: Color = Ui.GlassStrong,
    line: Color = Ui.Line,
    onClick: () -> Unit,
) {
    Box(
        modifier.size(size).glass(shape, background, line).clickable(onClick = onClick)
            .semantics { contentDescription = description; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.44f)) }
}

/** Status pill: coloured dot + short text. */
@Composable
fun StatusChip(text: String, dot: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier.glass(RoundedCornerShape(50)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).shadow(6.dp, CircleShape, ambientColor = dot, spotColor = dot).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, color = Ui.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** "Update available" banner (Home). Hidden when up to date or dismissed for this version. */
@Composable
fun UpdateBanner(vm: LinkViewModel, modifier: Modifier = Modifier) {
    val st by vm.c.updates.state.collectAsStateWithLifecycle()
    val r = st.available ?: return
    if (st.dismissedVersion == r.version && st.progress == null) return
    val context = LocalContext.current
    val cyan = LinkColors.Accent
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp), cyan.copy(alpha = 0.12f), cyan.copy(alpha = 0.35f)).padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_download), null, tint = Color(0xFF67E8F9), modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Update available · ${r.version}", color = Ui.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                val p = st.progress
                Text(
                    when {
                        p != null -> "Downloading… ${(p * 100).toInt()}%"
                        st.error != null -> st.error!!
                        else -> "You have ${vm.c.updates.currentVersion}" + if (r.sizeBytes > 0) " · ${r.sizeBytes / 1_000_000} MB" else ""
                    },
                    color = if (st.error != null && p == null) LinkColors.Warn else Color(0xFF9FB4C0), fontSize = 12.sp, maxLines = 2,
                )
            }
            if (st.progress == null) {
                Text(
                    "Later", color = Ui.Sub, fontSize = 13.sp,
                    modifier = Modifier.clickable { vm.c.updates.dismiss() }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
                Box(
                    Modifier.height(36.dp).background(cyan, RoundedCornerShape(18.dp)).clickable { vm.c.updates.downloadAndInstall(context) }.padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Install", color = Color(0xFF04222B), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
            }
        }
        st.progress?.let {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth().padding(end = 6.dp), color = cyan, trackColor = Color(0x33FFFFFF))
        }
    }
}

/** Bottom tab bar: plain icons and labels, the selected tab tinted. */
@Composable
fun SrcsTabBar(selected: String, accent: Color, badges: Map<String, Int>, onSelect: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Ui.Bar).height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple("home", "Home", R.drawable.ic_wave),
            Triple("team", "Team", R.drawable.ic_team),
            Triple("map", "Map", R.drawable.ic_map),
            Triple("chats", "Chats", R.drawable.ic_chat),
            Triple("me", "Me", R.drawable.ic_me),
        ).forEach { (id, label, icon) ->
            val on = id == selected
            Column(
                Modifier.weight(1f).clickable { onSelect(id) }.padding(vertical = 6.dp)
                    .semantics { role = Role.Tab; contentDescription = label },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val n = badges[id] ?: 0
                BadgedBox(badge = { if (n > 0) Badge { Text("$n") } }) {
                    Icon(painterResource(icon), null, tint = if (on) accent else Color(0xFF7B8A99), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(4.dp))
                Text(label, fontSize = 11.sp, color = if (on) Color.White else Color(0xFF7B8A99), fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
