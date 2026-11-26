package ai.paai.link.core.messaging

import ai.paai.link.core.group.GroupManager
import ai.paai.link.core.group.GroupRole
import ai.paai.link.core.node.Incoming
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.node.NodeEvent
import ai.paai.link.core.protocol.AckStatus
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 *  QUEUED -> SENT -> DELIVERED -> READ
 *     |        |
 *     v        v
 *  FAILED   RETRYING
 * RECEIVED is used for incoming messages.
 */
enum class MessageStatus { QUEUED, SENT, RETRYING, DELIVERED, READ, FAILED, RECEIVED }

data class StoredMessage(
    val messageId: DeviceId,
    /** Peer ID for direct conversations, group ID for group conversations. */
    val conversationId: DeviceId,
    val isGroup: Boolean,
    val author: DeviceId,
    val authorName: String,
    /** Outgoing: the device this copy is sent to (peer, or group host). Incoming: this device. */
    val recipient: DeviceId,
    val outgoing: Boolean,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long? = null,
    val status: MessageStatus,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val read: Boolean = false,
)

/** Persistent message storage. Implemented with Room on Android and in memory for tests. */
interface MessageStore {
    /** Returns false if a message with the same ID already exists (duplicate). */
    suspend fun insert(m: StoredMessage): Boolean
    suspend fun update(m: StoredMessage)
    suspend fun get(id: DeviceId): StoredMessage?
    suspend fun pendingOutgoing(): List<StoredMessage>
    suspend fun unreadIncoming(conversationId: DeviceId): List<StoredMessage>
    suspend fun deleteOlderThan(timestamp: Long): Int
}

class InMemoryMessageStore : MessageStore {
    private val map = java.util.concurrent.ConcurrentHashMap<DeviceId, StoredMessage>()
    val all: List<StoredMessage> get() = map.values.sortedBy { it.sentAt }
    override suspend fun insert(m: StoredMessage) = map.putIfAbsent(m.messageId, m) == null
    override suspend fun update(m: StoredMessage) { map[m.messageId] = m }
    override suspend fun get(id: DeviceId) = map[id]
    override suspend fun pendingOutgoing() = map.values.filter { it.outgoing && it.status in PENDING }
    override suspend fun unreadIncoming(conversationId: DeviceId) = map.values.filter { !it.outgoing && !it.read && it.conversationId == conversationId }
    override suspend fun deleteOlderThan(timestamp: Long): Int {
        val old = map.values.filter { it.sentAt < timestamp }
        old.forEach { map.remove(it.messageId) }
        return old.size
    }
}

val PENDING = setOf(MessageStatus.QUEUED, MessageStatus.SENT, MessageStatus.RETRYING)

data class MessagingConfig(
    val ackTimeoutMs: Long = 8_000,
    val maxBackoffMs: Long = 60_000,
    /** Undelivered messages become FAILED after this long. */
    val undeliveredExpiryMs: Long = 24 * 3_600_000L,
    /** Delete conversation history older than this (0 = keep forever). */
    val retentionMs: () -> Long = { 30L * 24 * 3_600_000L },
)

/**
 * Offline text messaging over end-to-end sealed messages. Every delivery is acknowledged by an
 * authenticated TEXT_ACK; unacknowledged messages are retried with exponential backoff while the
 * recipient is reachable, handed to a paired relay for store-and-forward when it is not, and marked
 * FAILED after [MessagingConfig.undeliveredExpiryMs]. Duplicates are detected by message ID.
 *
 * Group messages go to the group host, which re-seals them for every other member.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessagingService(
    private val node: LinkNode,
    private val store: MessageStore,
    private val groups: GroupManager?,
    private val scope: CoroutineScope,
    private val config: MessagingConfig = MessagingConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val actor = Dispatchers.Default.limitedParallelism(1)
    private val _received = MutableSharedFlow<StoredMessage>(extraBufferCapacity = 64)
    /** Newly received messages (for notifications). */
    val received: SharedFlow<StoredMessage> = _received.asSharedFlow()

    init {
        scope.launch { node.incoming.collect { withContext(actor) { onIncoming(it) } } }
        scope.launch {
            node.events.collect { e ->
                if (e is NodeEvent.PeerReachable) withContext(actor) { flush(onlyTo = e.peer) }
            }
        }
        scope.launch(actor) {
            var purgeTick = 0
            while (isActive) {
                delay(2_000)
                flush()
                if (purgeTick++ % 1_800 == 0) purge()
            }
        }
    }

    /** Queue a direct message to a paired device. */
    suspend fun send(to: DeviceId, body: String): StoredMessage = withContext(actor) {
        val text = clean(body)
        val m = StoredMessage(DeviceId.random(), to, false, node.selfId, node.displayName, to, true, text, clock(), status = MessageStatus.QUEUED)
        store.insert(m)
        attempt(m)
    }

    /** Send a message to the current group. */
    suspend fun sendToGroup(body: String): StoredMessage? = withContext(actor) {
        val g = groups?.group?.value ?: return@withContext null
        val text = clean(body)
        val host = g.host ?: return@withContext null
        val m = StoredMessage(DeviceId.random(), g.groupId, true, node.selfId, node.displayName, host, true, text, clock(), status = MessageStatus.QUEUED)
        store.insert(m)
        if (g.role == GroupRole.HOST) {
            // The host distributes directly; members' acks move it to DELIVERED.
            distribute(m, except = node.selfId)
            val sent = m.copy(status = MessageStatus.SENT, attempts = 1)
            store.update(sent)
            sent
        } else attempt(m)
    }

    /** Mark a conversation read and send read receipts for direct messages. */
    suspend fun markRead(conversationId: DeviceId) = withContext(actor) {
        for (m in store.unreadIncoming(conversationId)) {
            store.update(m.copy(read = true))
            if (!m.isGroup) node.send(m.author, AppMessage.TextAck(m.messageId, AckStatus.READ), storeAndForward = true)
        }
    }

    /** Retry a FAILED message. */
    suspend fun retry(messageId: DeviceId) = withContext(actor) {
        val m = store.get(messageId) ?: return@withContext
        if (m.outgoing && m.status == MessageStatus.FAILED) attempt(m.copy(status = MessageStatus.QUEUED, attempts = 0, sentAt = m.sentAt))
    }

    // ------------------------------------------------------------------ internals

    private fun clean(body: String): String {
        val t = body.trim()
        require(t.isNotEmpty()) { "empty message" }
        var s = t
        while (s.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_TEXT_BYTES) s = s.dropLast(1)
        return s
    }

    private fun toWire(m: StoredMessage) = AppMessage.Text(
        m.messageId, if (m.isGroup) m.conversationId else DeviceId.ZERO, m.author, m.authorName.take(40), m.sentAt, m.body,
    )

    private suspend fun attempt(m: StoredMessage): StoredMessage {
        val now = clock()
        val ok = node.send(m.recipient, toWire(m), storeAndForward = true)
        val updated = if (ok) {
            val attempts = m.attempts + 1
            m.copy(
                status = if (attempts > 1) MessageStatus.RETRYING else MessageStatus.SENT,
                attempts = attempts,
                nextAttemptAt = now + backoff(attempts),
            )
        } else {
            m.copy(status = MessageStatus.QUEUED, nextAttemptAt = now + 5_000)
        }
        store.update(updated)
        return updated
    }

    private fun backoff(attempts: Int): Long =
        (config.ackTimeoutMs shl (attempts - 1).coerceIn(0, 6)).coerceAtMost(config.maxBackoffMs)

    private suspend fun flush(onlyTo: DeviceId? = null) {
        val now = clock()
        for (m in store.pendingOutgoing()) {
            if (onlyTo != null && m.recipient != onlyTo) continue
            if (m.recipient == node.selfId) continue // host's own group message, already distributed
            if (now - m.sentAt > config.undeliveredExpiryMs) {
                store.update(m.copy(status = MessageStatus.FAILED))
                node.log.i(TAG, "message to ${node.peerName(m.recipient)} expired undelivered")
                continue
            }
            if (onlyTo == null && now < m.nextAttemptAt) continue
            if (m.isGroup && groups?.group?.value?.groupId != m.conversationId) {
                store.update(m.copy(status = MessageStatus.FAILED))
                continue
            }
            attempt(m)
        }
    }

    private suspend fun purge() {
        val keep = config.retentionMs()
        if (keep <= 0) return
        val n = store.deleteOlderThan(clock() - keep)
        if (n > 0) node.log.i(TAG, "deleted $n messages past retention")
    }

    private suspend fun distribute(m: StoredMessage, except: DeviceId) {
        val g = groups?.group?.value ?: return
        val wire = toWire(m)
        for (member in g.members) {
            if (member.id == node.selfId || member.id == except) continue
            node.send(member.id, wire, storeAndForward = true)
        }
    }

    private suspend fun onIncoming(inc: Incoming) {
        when (val msg = inc.message) {
            is AppMessage.Text -> onText(inc.from, msg)
            is AppMessage.TextAck -> onAck(inc.from, msg)
            else -> Unit
        }
    }

    private suspend fun onText(from: DeviceId, t: AppMessage.Text) {
        val now = clock()
        if (t.groupId.isZero) {
            if (t.origin != from) return // direct messages must come from their author
            val m = StoredMessage(t.messageId, from, false, from, node.peerName(from), node.selfId, false, t.body, t.sentAt, now, MessageStatus.RECEIVED)
            val fresh = store.insert(m)
            node.send(from, AppMessage.TextAck(t.messageId, AckStatus.DELIVERED), storeAndForward = true)
            if (fresh) _received.tryEmit(m)
            return
        }
        val g = groups?.group?.value
        if (g == null || g.groupId != t.groupId) return
        val fromIsHost = from == g.host
        val iAmHost = g.role == GroupRole.HOST
        val authorised = (fromIsHost && !iAmHost) || (iAmHost && t.origin == from && g.members.any { it.id == from })
        if (!authorised) return
        val m = StoredMessage(t.messageId, t.groupId, true, t.origin, t.originName, node.selfId, false, t.body, t.sentAt, now, MessageStatus.RECEIVED)
        val fresh = store.insert(m)
        node.send(from, AppMessage.TextAck(t.messageId, AckStatus.DELIVERED), storeAndForward = true)
        if (fresh) {
            if (iAmHost) distribute(m, except = from)
            _received.tryEmit(m)
        }
    }

    private suspend fun onAck(from: DeviceId, a: AppMessage.TextAck) {
        val m = store.get(a.messageId) ?: return
        if (!m.outgoing) return
        val allowed = if (m.isGroup) groups?.group?.value?.members?.any { it.id == from } == true else m.recipient == from
        if (!allowed) return
        val newStatus = when {
            m.status == MessageStatus.READ -> return
            a.status == AckStatus.READ -> MessageStatus.READ
            else -> MessageStatus.DELIVERED
        }
        if (newStatus != m.status) store.update(m.copy(status = newStatus))
    }

    companion object {
        private const val TAG = "messages"
    }
}
