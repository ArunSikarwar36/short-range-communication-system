package ai.paai.link.ui

import ai.paai.link.core.location.Geo
import ai.paai.link.core.location.GeoFix
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

private val SosRed = Color(0xFFE5484D)

private fun distanceText(me: GeoFix?, lat: Double, lon: Double): String =
    if (me == null) "" else Geo.formatDistance(Geo.distanceM(me.lat, me.lon, lat, lon)) + " " + Geo.compass(Geo.bearingDeg(me.lat, me.lon, lat, lon))

/** Opens the position in an installed map app (offline apps such as OsmAnd or Organic Maps work without internet). */
fun openInMapApp(context: Context, lat: Double, lon: Double, label: String) {
    val uri = "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(label)})".toUri()
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No map app installed. %.5f, %.5f".format(lat, lon), Toast.LENGTH_LONG).show()
    }
}

/** Incoming SOS alerts, shown at the top of the home screen until dismissed. */
@Composable
fun SosAlerts(vm: LinkViewModel, showOnMap: (Double, Double, String) -> Unit) {
    val alerts by vm.activeSos.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    val context = LocalContext.current
    alerts.forEach { a ->
        Column(
            Modifier.fillMaxWidth().background(SosRed.copy(alpha = 0.18f), RoundedCornerShape(16.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("SOS from ${a.name}", color = SosRed, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            if (a.note.isNotBlank()) Text(a.note)
            Hint(
                (a.fix?.let { f -> distanceText(me, f.lat, f.lon).ifBlank { "%.5f, %.5f".format(f.lat, f.lon) } } ?: "No GPS position in the alert") +
                    " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.receivedAt)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                a.fix?.let { f -> Button(onClick = { showOnMap(f.lat, f.lon, "SOS ${a.name}") }) { Text("Show on map") } }
                OutlinedButton(onClick = { vm.dismissSos(a.alertId) }) { Text("Dismiss") }
            }
        }
    }
}

@Composable
fun LocationCard(vm: LinkViewModel, gate: PermissionGate, showOnMap: (Double, Double, String) -> Unit) {
    val sharing by vm.sharing.collectAsStateWithLifecycle()
    val friends by vm.friendLocations.collectAsStateWithLifecycle()
    val me by vm.myFix.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var choosing by remember { mutableStateOf(false) }
    var sosDialog by remember { mutableStateOf(false) }

    SectionCard("Location") {
        if (sharing.active) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(LinkColors.Good, "sharing")
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sharing your location")
                    Hint(
                        listOfNotNull(if (sharing.toPaired) "paired phones" else null, if (sharing.toGroup) "your group" else null).joinToString(" and ") +
                            " · until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sharing.until)) +
                            (if (me == null) " · waiting for GPS" else " · ${sharing.recipients} receiving"),
                    )
                }
                OutlinedButton(onClick = { vm.stopSharing() }) { Text("Stop") }
            }
        } else {
            Hint("Off. Your position is only sent when you choose to share it, and only to the phones you pick.")
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sharing.active) {
                Button(onClick = { gate.withPermissions(Perms.location, Reasons.LOCATION) { choosing = true } }) { Text("Share my location") }
            }
            Button(
                onClick = { gate.withPermissions(emptyArray(), Reasons.LOCATION, Perms.location) { sosDialog = true } },
                colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
            ) { Text("SOS", fontWeight = FontWeight.Bold) }
        }
        if (friends.isNotEmpty()) {
            Text("Friends sharing with you", style = MaterialTheme.typography.labelLarge, color = LinkColors.Muted)
            friends.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(f.name, active = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name)
                        val age = ((System.currentTimeMillis() - f.fixAt) / 60_000).coerceAtLeast(0)
                        Hint(
                            listOf(
                                distanceText(me, f.lat, f.lon).ifBlank { "Your GPS position is not known yet" },
                                if (age == 0L) "just now" else "$age min ago",
                                if (f.viaGroup) "via group" else "",
                            ).filter { it.isNotBlank() }.joinToString(" · "),
                        )
                    }
                    TextButton(onClick = { showOnMap(f.lat, f.lon, f.name) }) { Text("Map") }
                }
            }
        }
    }

    if (choosing) {
        var toPaired by remember { mutableStateOf(true) }
        var toGroup by remember { mutableStateOf(group != null) }
        var minutes by remember { mutableIntStateOf(60) }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Share my location") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(toPaired, { toPaired = it }); Text("Paired phones in range")
                    }
                    if (group != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(toGroup, { toGroup = it }); Text("My group '${group!!.name}'")
                    }
                    Text("For", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15 to "15 min", 60 to "1 hour", 480 to "8 hours").forEach { (m, label) ->
                            FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(label) })
                        }
                    }
                    Hint("Updates are sent when you move (at most every 5 s) and stop automatically at the end.")
                }
            },
            confirmButton = {
                TextButton(enabled = toPaired || toGroup, onClick = { choosing = false; vm.startSharing(toPaired, toGroup, minutes) }) { Text("Start sharing") }
            },
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }

    if (sosDialog) {
        var note by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { sosDialog = false },
            title = { Text("Send SOS?", color = SosRed) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Every paired phone in range and your group get an alarm with your position (if GPS finds you). Phones out of range get it when they come back.")
                    OutlinedTextField(note, { note = it.take(140) }, label = { Text("What happened? (optional)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = { sosDialog = false; vm.sendSos(note) },
                    colors = ButtonDefaults.buttonColors(containerColor = SosRed, contentColor = Color.White),
                ) { Text("Send SOS") }
            },
            dismissButton = { TextButton(onClick = { sosDialog = false }) { Text("Cancel") } },
        )
    }
}
