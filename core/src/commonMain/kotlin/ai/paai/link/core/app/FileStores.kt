package ai.paai.link.core.app

import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.messaging.MessageStore
import ai.paai.link.core.messaging.PENDING
import ai.paai.link.core.messaging.StoredMessage
import ai.paai.link.core.node.TrustStore
import ai.paai.link.core.node.TrustedPeer
import ai.paai.link.core.util.ByteReader
import ai.paai.link.core.util.ByteWriter
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.Files
import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Trust records in one small binary file, rewritten atomically on every change.
 * Used on iOS (Android keeps them in Room). A damaged file is treated as empty.
 */
class FileTrustStore(private val path: String) : TrustStore {
    private val lock = Lock()
    private var cache: LinkedHashMap<DeviceId, TrustedPeer>? = null
    private val _version = MutableStateFlow(0L)
    /** Increments on every change, for UI refresh. */
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun load(): LinkedHashMap<DeviceId, TrustedPeer> {
        cache?.let { return it }
        val m = LinkedHashMap<DeviceId, TrustedPeer>()
        Files.read(path)?.let { bytes ->
            runCatching {
                val r = ByteReader(bytes)
                require(r.u8() == VERSION)
                repeat(r.u32().toInt()) {
                    val id = r.id()
                    val key = r.blob(64)
                    val name = r.string(256)
                    val at = r.u64()
                    m[id] = TrustedPeer(id, key, name, at)
                }
            }.onFailure { m.clear() }
        }
        cache = m
        return m
    }

    private fun persist(m: Map<DeviceId, TrustedPeer>) {
        val w = ByteWriter().u8(VERSION).u32(m.size.toLong())
        for (p in m.values) w.raw(p.id.toBytes()).blob(p.staticKey, 64).string(p.name.take(60), 256).u64(p.pairedAt)
        Files.writeAtomic(path, w.bytes())
        _version.value++
    }

    fun all(): List<TrustedPeer> = lock.withLock { load().values.toList() }

    override suspend fun loadAll(): List<TrustedPeer> = all()
    override suspend fun save(peer: TrustedPeer) = lock.withLock { val m = load(); m[peer.id] = peer; persist(m) }
    override suspend fun remove(id: DeviceId) = lock.withLock { val m = load(); if (m.remove(id) != null) persist(m) }

    private companion object { const val VERSION = 1 }
}

/**
 * Messages in one binary file, rewritten atomically on every change and capped at [maxMessages]
 * (oldest dropped). Fine for a chat app on one phone; Android uses Room.
 */
class FileMessageStore(private val path: String, private val maxMessages: Int = 5_000) : MessageStore {
    private val lock = Lock()
    private var cache: LinkedHashMap<DeviceId, StoredMessage>? = null
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private fun load(): LinkedHashMap<DeviceId, StoredMessage> {
        cache?.let { return it }
        val m = LinkedHashMap<DeviceId, StoredMessage>()
        Files.read(path)?.let { bytes ->
            runCatching {
                val r = ByteReader(bytes)
                require(r.u8() == VERSION)
                repeat(r.u32().toInt()) {
                    val msg = StoredMessage(
                        messageId = r.id(), conversationId = r.id(), isGroup = r.bool(), author = r.id(),
                        authorName = r.string(256), recipient = r.id(), outgoing = r.bool(), body = r.string(65_535),
                        sentAt = r.u64(), receivedAt = r.u64().takeIf { it != NONE },
                        status = MessageStatus.entries[r.u8()], attempts = r.u32().toInt(), nextAttemptAt = r.u64(), read = r.bool(),
                    )
                    m[msg.messageId] = msg
                }
            }.onFailure { m.clear() }
        }
        cache = m
        return m
    }

    private fun persist(m: LinkedHashMap<DeviceId, StoredMessage>) {
        if (m.size > maxMessages) {
            m.values.sortedBy { it.sentAt }.take(m.size - maxMessages).forEach { m.remove(it.messageId) }
        }
        val w = ByteWriter(1024).u8(VERSION).u32(m.size.toLong())
        for (x in m.values) {
            w.raw(x.messageId.toBytes()).raw(x.conversationId.toBytes()).bool(x.isGroup).raw(x.author.toBytes())
                .string(x.authorName.take(60), 256).raw(x.recipient.toBytes()).bool(x.outgoing).string(x.body, 65_535)
                .u64(x.sentAt).u64(x.receivedAt ?: NONE).u8(x.status.ordinal).u32(x.attempts.toLong()).u64(x.nextAttemptAt).bool(x.read)
        }
        Files.writeAtomic(path, w.bytes())
        _version.value++
    }

    /** Messages of one conversation (peer ID or group ID), oldest first. */
    fun conversation(id: DeviceId): List<StoredMessage> = lock.withLock { load().values.filter { it.conversationId == id }.sortedBy { it.sentAt } }

    fun all(): List<StoredMessage> = lock.withLock { load().values.toList() }

    fun deleteConversation(id: DeviceId) = lock.withLock {
        val m = load()
        if (m.values.removeAll { it.conversationId == id }) persist(m)
    }

    override suspend fun insert(m: StoredMessage): Boolean {
        lock.withLock {
            val all = load()
            if (all.containsKey(m.messageId)) return false
            all[m.messageId] = m
            persist(all)
        }
        return true
    }

    override suspend fun update(m: StoredMessage) = lock.withLock { val all = load(); all[m.messageId] = m; persist(all) }
    override suspend fun get(id: DeviceId): StoredMessage? = lock.withLock { load()[id] }
    override suspend fun pendingOutgoing(): List<StoredMessage> = lock.withLock { load().values.filter { it.outgoing && it.status in PENDING } }
    override suspend fun unreadIncoming(conversationId: DeviceId): List<StoredMessage> =
        lock.withLock { load().values.filter { !it.outgoing && !it.read && it.conversationId == conversationId } }

    override suspend fun deleteOlderThan(timestamp: Long): Int = lock.withLock {
        val all = load()
        val old = all.values.filter { it.sentAt < timestamp }.map { it.messageId }
        old.forEach { all.remove(it) }
        if (old.isNotEmpty()) persist(all)
        old.size
    }

    private companion object {
        const val VERSION = 1
        const val NONE = -1L
    }
}
