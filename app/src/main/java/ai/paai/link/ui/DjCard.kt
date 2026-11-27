package ai.paai.link.ui

import ai.paai.link.core.dj.DjRole
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * DJ Mode in a group: the host picks songs from this phone and everyone in the group hears them;
 * members see what is playing and can request songs.
 */
@Composable
fun DjCard(vm: LinkViewModel, isHost: Boolean) {
    val dj by vm.djState.collectAsStateWithLifecycle()
    val playlist by vm.playlist.collectAsStateWithLifecycle()
    val current by vm.playlistIndex.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.djAddSongs(uris) }
    var request by remember { mutableStateOf("") }

    GradientCard("DJ MODE" + if (dj.role == DjRole.DJ) " · YOU'RE HOSTING" else "", listOf(Color(0x598B5CF6), Color(0x38EC4899)), Color(0x4DC4A0FF)) {
        when (dj.role) {
            DjRole.NONE -> {
                if (isHost) {
                    Hint("Play songs from this phone to everyone in the group, in sync. Only music you have on the phone is shared.")
                    Button(onClick = { vm.djStart() }) { Text("Start DJ Mode") }
                } else {
                    Hint("When the host starts DJ Mode, the music plays here automatically.")
                }
            }
            DjRole.DJ -> {
                NowPlaying(dj.track?.title, dj.track?.artist, dj.playing, dj.positionMs, dj.track?.durationMs ?: 0)
                Hint("${dj.listeners} listening")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (dj.track != null && !dj.ended) {
                        Button(onClick = { vm.djPauseResume() }) { Text(if (dj.playing) "Pause" else "Play") }
                    }
                    if (current + 1 < playlist.size) OutlinedButton(onClick = { vm.djPlay(current + 1) }) { Text("Next") }
                    OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }) { Text("Add songs") }
                    TextButton(onClick = { vm.djStop() }) { Text("End DJ Mode") }
                }
                if (playlist.isNotEmpty()) {
                    Text("Playlist", style = MaterialTheme.typography.labelLarge, color = LinkColors.Muted)
                    playlist.forEachIndexed { i, s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.djPlay(i) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (i == current) "▶ " else "   ", color = LinkColors.Accent)
                            Column(Modifier.weight(1f)) {
                                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (i == current) FontWeight.Bold else null)
                                if (s.artist.isNotBlank()) Hint(s.artist)
                            }
                            Hint(formatDuration(s.durationMs))
                        }
                    }
                } else {
                    Hint("Add songs from this phone to start playing.")
                }
                if (dj.requests.isNotEmpty()) {
                    Text("Requests", style = MaterialTheme.typography.labelLarge, color = LinkColors.Muted)
                    dj.requests.reversed().forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${r.name}: ${r.text}", Modifier.weight(1f))
                            TextButton(onClick = { vm.djClearRequest(r) }) { Text("Done") }
                        }
                    }
                }
            }
            DjRole.LISTENER -> {
                Hint("DJ: ${dj.djName}")
                NowPlaying(dj.track?.title, dj.track?.artist, dj.playing, dj.positionMs, dj.track?.durationMs ?: 0)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(request, { request = it.take(120) }, label = { Text("Request a song") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = request.isNotBlank(), onClick = { vm.djRequest(request); request = "" }) { Text("Send") }
                }
                TextButton(onClick = { vm.djStop() }) { Text("Stop listening") }
            }
        }
    }
}

@Composable
private fun NowPlaying(title: String?, artist: String?, playing: Boolean, positionMs: Long, durationMs: Long) {
    if (title == null) {
        Hint("Nothing playing yet")
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(52.dp).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFFA78BFA), Color(0xFFEC4899))), androidx.compose.foundation.shape.RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(ai.paai.link.R.drawable.ic_music), null, tint = Color.White, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!artist.isNullOrBlank()) Hint(artist)
        }
    }
    if (durationMs > 0) LinearProgressIndicator({ (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) }, Modifier.fillMaxWidth(), color = Color.White, trackColor = Color(0x2EFFFFFF))
    Hint((if (playing) "Playing" else "Paused") + " · " + formatDuration(positionMs) + if (durationMs > 0) " / " + formatDuration(durationMs) else "")
}
