package ai.paai.link.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object LinkColors {
    val Background = Color(0xFF0A0F16)
    val Surface = Color(0xFF121A24)
    val SurfaceHigh = Color(0xFF1A2532)
    val Accent = Color(0xFF22C7E6)
    val AccentDim = Color(0xFF1B6F82)
    val OnSurface = Color(0xFFE6F1F5)
    val Muted = Color(0xFF8FA3B0)
    val Good = Color(0xFF3DDC97)
    val Warn = Color(0xFFF5B942)
    val Bad = Color(0xFFFF5C6C)
}

private val scheme = darkColorScheme(
    primary = LinkColors.Accent,
    onPrimary = Color(0xFF00222B),
    primaryContainer = LinkColors.AccentDim,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF7FB8CC),
    background = LinkColors.Background,
    onBackground = LinkColors.OnSurface,
    surface = LinkColors.Surface,
    onSurface = LinkColors.OnSurface,
    surfaceVariant = LinkColors.SurfaceHigh,
    onSurfaceVariant = LinkColors.Muted,
    surfaceContainer = LinkColors.Surface,
    surfaceContainerHigh = LinkColors.SurfaceHigh,
    error = LinkColors.Bad,
)

@Composable
fun LinkTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = LinkColors.Surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                action?.invoke()
            }
            content()
        }
    }
}

/** Colored status dot with an accessible description (color is never the only signal). */
@Composable
fun StatusDot(color: Color, description: String) {
    Box(
        Modifier
            .size(10.dp)
            .background(color, CircleShape)
            .semantics { contentDescription = description },
    )
}

@Composable
fun StatusLine(color: Color, text: String, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, text)
        Spacer(Modifier.width(8.dp))
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = LinkColors.Muted)
        }
    }
}

@Composable
fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = LinkColors.Muted)

@Composable
fun Avatar(name: String, active: Boolean = false, sizeDp: Int = 40) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .background(if (active) LinkColors.Accent else LinkColors.SurfaceHigh, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            color = if (active) Color(0xFF00222B) else LinkColors.OnSurface,
            fontSize = (sizeDp / 2.4).sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

fun formatTime(ts: Long): String {
    val now = System.currentTimeMillis()
    val fmt = if (now - ts < 20 * 3_600_000L) "HH:mm" else "d MMM HH:mm"
    return java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault()).format(java.util.Date(ts))
}
