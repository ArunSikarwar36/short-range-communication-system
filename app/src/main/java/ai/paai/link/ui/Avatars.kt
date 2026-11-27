package ai.paai.link.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/** Distinct, friendly colours for people's avatar bubbles (stable per name). */
private val palette = listOf(
    Color(0xFF8E6CF0), Color(0xFFF59E0B), Color(0xFF22C7E6), Color(0xFFEC4899),
    Color(0xFF3DDC97), Color(0xFF60A5FA), Color(0xFFF97316), Color(0xFFA3E635),
)

fun avatarColor(name: String): Color = palette[abs(name.trim().lowercase().hashCode()) % palette.size]

val SosRedColor = Color(0xFFE5484D)
val TeamTeal = Color(0xFF14B8A6)
val ChatAmber = Color(0xFFFBBF24)
val DjPurple = Color(0xFF8E6CF0)

/** Round avatar with the person's initial on a colour gradient, optional ring and name tag. */
@Composable
fun AvatarBubble(
    name: String,
    modifier: Modifier = Modifier,
    sizeDp: Int = 48,
    ring: Color? = null,
    label: String? = null,
    sub: String? = null,
) {
    val c = avatarColor(name)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(sizeDp.dp)
                .background(Brush.linearGradient(listOf(c, c.copy(alpha = 0.65f))), CircleShape)
                .border((if (ring != null) 3 else 2).dp, ring ?: Color.White.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (sizeDp / 2.3).sp)
        }
        if (label != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                color = Color.White, fontWeight = FontWeight.Medium,
                modifier = Modifier.widthIn(max = 84.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        if (sub != null) Text(sub, fontSize = 10.sp, color = LinkColors.Muted, maxLines = 1)
    }
}

/** Confirm and send an SOS with an optional note (asks for location once, sends anyway). */
@Composable
fun SosDialog(vm: LinkViewModel, onDismiss: () -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send SOS?", color = SosRedColor, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Every paired phone in range and your team get an alarm with your position (if GPS finds you). Phones out of range get it when they come back.")
                OutlinedTextField(note, { note = it.take(140) }, label = { Text("What happened? (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            Button(onClick = { onDismiss(); vm.sendSos(note) }, colors = ButtonDefaults.buttonColors(containerColor = SosRedColor, contentColor = Color.White)) {
                Text("Send SOS")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
