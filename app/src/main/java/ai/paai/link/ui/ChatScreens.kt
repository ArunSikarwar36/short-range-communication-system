package ai.paai.link.ui

import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.util.DeviceId
import ai.paai.link.data.MessageEntity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(vm: LinkViewModel, onOpen: (DeviceId) -> Unit, onBack: (() -> Unit)?) {
    val rows by vm.conversations.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Messages") },
            navigationIcon = { if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (rows.isEmpty()) item { Hint("No conversations yet. Messages are stored only on this phone.") }
            items(rows, key = { it.id.hex }) { r ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(r.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(r.title)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title + if (r.isGroup) "  (group)" else "")
                        Text(
                            (if (r.last.outgoing) "You: " else "") + r.last.body,
                            maxLines = 1, style = MaterialTheme.typography.bodySmall, color = LinkColors.Muted,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Hint(formatTime(r.last.sentAt))
                        if (r.unread > 0) Badge { Text("${r.unread}") }
                    }
                }
            }
            val withoutChat = paired.filter { p -> rows.none { it.id.hex == p.id } }
            if (withoutChat.isNotEmpty()) item {
                Spacer(Modifier.padding(top = 12.dp))
                Hint("Start a conversation")
            }
            items(withoutChat, key = { "new-" + it.id }) { p ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(DeviceId.fromHex(p.id)) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(p.displayName)
                    Spacer(Modifier.width(12.dp))
                    Text(p.displayName)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: LinkViewModel, id: DeviceId, onBack: () -> Unit) {
    val messages by remember(id) { vm.conversation(id) }.collectAsState(initial = emptyList())
    val rows by vm.conversations.collectAsStateWithLifecycle()
    val paired by vm.paired.collectAsStateWithLifecycle()
    val group by vm.group.collectAsStateWithLifecycle()
    val peers by vm.peerStatus.collectAsStateWithLifecycle()
    val isGroup = group?.groupId == id || rows.firstOrNull { it.id == id }?.isGroup == true
    val title = group?.takeIf { it.groupId == id }?.name ?: paired.firstOrNull { it.id == id.hex }?.displayName ?: rows.firstOrNull { it.id == id }?.title ?: id.short
    val reachable = peers.any { it.id == id && it.reachable }
    var text by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    DisposableEffect(id) {
        vm.setOpenConversation(id)
        onDispose { vm.setOpenConversation(null) }
    }
    LaunchedEffect(messages.size) {
        vm.markRead(id)
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text(title)
                    Hint(
                        when {
                            isGroup -> if (group?.groupId == id) "Group · ${group?.members?.size} members" else "Group ended (read only)"
                            reachable -> "Reachable"
                            else -> "Not in range · messages wait and are sent when reachable"
                        },
                    )
                }
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete conversation") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.messageId }) { m -> Bubble(m, isGroup) { vm.retry(DeviceId.fromHex(m.messageId)) } }
        }
        val canSend = !isGroup || group?.groupId == id
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it.take(2000) },
                modifier = Modifier.weight(1f),
                enabled = canSend,
                placeholder = { Text(if (canSend) "Message" else "Group has ended") },
                maxLines = 4,
            )
            IconButton(
                enabled = canSend && text.isNotBlank(),
                onClick = {
                    if (isGroup) vm.sendGroup(text) else vm.send(id, text)
                    text = ""
                },
            ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send") }
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete conversation?") },
        text = { Text("Messages are removed from this phone only.") },
        confirmButton = { TextButton(onClick = { vm.deleteConversation(id); confirmDelete = false; onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Bubble(m: MessageEntity, isGroup: Boolean, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(if (m.outgoing) LinkColors.AccentDim else LinkColors.SurfaceHigh, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (isGroup && !m.outgoing) Text(m.authorName, style = MaterialTheme.typography.labelSmall, color = LinkColors.Accent)
            Text(m.body)
            val status = if (m.outgoing) when (MessageStatus.valueOf(m.status)) {
                MessageStatus.QUEUED -> "Waiting for device"
                MessageStatus.SENT -> "Sent"
                MessageStatus.RETRYING -> "Retrying…"
                MessageStatus.DELIVERED -> "Delivered"
                MessageStatus.READ -> "Read"
                MessageStatus.FAILED -> "Not delivered · tap to retry"
                MessageStatus.RECEIVED -> ""
            } else ""
            Row {
                Text(formatTime(m.sentAt), style = MaterialTheme.typography.labelSmall, color = LinkColors.Muted)
                if (status.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (m.status == MessageStatus.FAILED.name) LinkColors.Bad else LinkColors.Muted,
                        modifier = if (m.status == MessageStatus.FAILED.name) Modifier.clickable(onClick = onRetry) else Modifier,
                    )
                }
            }
        }
    }
}
