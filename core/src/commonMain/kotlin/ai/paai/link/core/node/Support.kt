package ai.paai.link.core.node

import ai.paai.link.core.util.nanoTime
import ai.paai.link.core.util.currentTimeMillis
import ai.paai.link.core.util.ConcurrentMap
import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.withLock
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NodeConfig(
    val tcpPort: Int = 47700,
    val udpPort: Int = 47701,
    val bindHost: String? = null,
    val advertIntervalMs: Long = 3_000,
    val pingIntervalMs: Long = 4_000,
    val linkTimeoutMs: Long = 15_000,
    val routeTimeoutMs: Long = 12_000,
    val pairingTimeoutMs: Long = 120_000,
    /** Sealed messages older than this (sender clock) are discarded as stale. */
    val maxMessageAgeMs: Long = 24 * 3_600_000L,
    val storeForwardCapacity: Int = 200,
    val storeForwardTtlMs: Long = 24 * 3_600_000L,
    /** Reconnect to an address we dialled for this long after the link drops. */
    val reconnectWindowMs: Long = 60_000,
    /** Per-source limit for media packets this device forwards for others. */
    val maxForwardedPacketsPerSecond: Int = 120,
)

/** A device the user has paired with. The ID is derived from [staticKey]. */
class TrustedPeer(val id: DeviceId, val staticKey: ByteArray, val name: String, val pairedAt: Long)

/** Persistent trust records. Implemented with Room on Android and in memory for tests. */
interface TrustStore {
    suspend fun loadAll(): List<TrustedPeer>
    suspend fun save(peer: TrustedPeer)
    suspend fun remove(id: DeviceId)
}

class InMemoryTrustStore : TrustStore {
    private val map = ConcurrentMap<DeviceId, TrustedPeer>()
    override suspend fun loadAll() = map.values.toList()
    override suspend fun save(peer: TrustedPeer) { map[peer.id] = peer }
    override suspend fun remove(id: DeviceId) { map.remove(id) }
}

/** Snapshot of a direct link, for UI and diagnostics. */
data class LinkInfo(
    val linkId: Long,
    val remoteId: DeviceId,
    val remoteName: String,
    val address: String,
    val initiator: Boolean,
    val trusted: Boolean,
    val sas: String,
    val localConfirmed: Boolean,
    val remoteConfirmed: Boolean,
    val connectedAt: Long,
    val rttMs: Long?,
    val bytesSent: Long,
    val bytesReceived: Long,
    val relayCapable: Boolean,
)

/** A paired or reachable device. */
data class PeerStatus(
    val id: DeviceId,
    val name: String,
    val trusted: Boolean,
    val reachable: Boolean,
    val hops: Int?,
    val via: DeviceId?,
    val direct: Boolean,
)

/** An authenticated application message from a paired device. */
data class Incoming(val from: DeviceId, val sentAt: Long, val message: AppMessage)

sealed class NodeEvent {
    data class PairingRequested(val link: LinkInfo) : NodeEvent()
    data class Paired(val peer: DeviceId, val name: String) : NodeEvent()
    data class PairingFailed(val remoteName: String, val reason: String) : NodeEvent()
    data class PeerReachable(val peer: DeviceId) : NodeEvent()
    data class PeerUnreachable(val peer: DeviceId) : NodeEvent()
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }
data class LogLine(val at: Long, val level: LogLevel, val tag: String, val message: String)

/**
 * In-memory diagnostic log shown in the app. Callers must never log key material,
 * message bodies or audio.
 */
class LogBuffer(private val capacity: Int = 400, private val sink: ((LogLine) -> Unit)? = null) {
    private val lock = Lock()
    private val _lines = MutableStateFlow<List<LogLine>>(emptyList())
    val lines: StateFlow<List<LogLine>> = _lines.asStateFlow()

    fun log(level: LogLevel, tag: String, message: String) {
        val line = LogLine(currentTimeMillis(), level, tag, message)
        sink?.invoke(line)
        lock.withLock { _lines.value = (_lines.value + line).takeLast(capacity) }
    }

    fun d(tag: String, m: String) = log(LogLevel.DEBUG, tag, m)
    fun i(tag: String, m: String) = log(LogLevel.INFO, tag, m)
    fun w(tag: String, m: String) = log(LogLevel.WARN, tag, m)
    fun e(tag: String, m: String) = log(LogLevel.ERROR, tag, m)
}

/** Simple token bucket for forwarding congestion control. */
class TokenBucket(private val ratePerSec: Int, private val burst: Int = ratePerSec) {
    private val lock = Lock()
    private var tokens = burst.toDouble()
    private var last = nanoTime()

    fun tryTake(): Boolean {
        return lock.withLock {
            val now = nanoTime()
            tokens = minOf(burst.toDouble(), tokens + (now - last) / 1e9 * ratePerSec)
            last = now
            if (tokens < 1.0) return false
            tokens -= 1.0
            return true
        }
    }
}
