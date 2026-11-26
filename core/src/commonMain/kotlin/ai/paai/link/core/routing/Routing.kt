package ai.paai.link.core.routing

import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.withLock
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.protocol.RouteEntry
import ai.paai.link.core.util.DeviceId

data class Route(
    val dest: DeviceId,
    val nextHop: DeviceId,
    val hops: Int,
    val seq: Long,
    val name: String,
    val updatedAt: Long,
)

/**
 * Distance-vector routing with DSDV destination sequence numbers.
 *
 * Loop prevention: each destination stamps its own advertisements with an increasing even sequence
 * number; routes are only replaced by fresher information (higher seq) or by an equally fresh route
 * with fewer hops. Routes learned from a neighbour are never advertised back to it (split horizon),
 * hop counts are capped at [ProtocolLimits.MAX_HOPS], and broken routes are advertised with an odd
 * sequence number and infinite metric so they are withdrawn quickly instead of waiting for expiry.
 *
 * Not thread-safe; the owning node serialises access.
 */
class RouteTable(
    private val self: DeviceId,
    private val routeTimeoutMs: Long = 15_000,
) {
    private val routes = HashMap<DeviceId, Route>()
    private val withdrawn = HashMap<DeviceId, RouteEntry>()
    private var selfSeq = 0L

    fun snapshot(): List<Route> = routes.values.sortedWith(compareBy({ it.hops }, { it.name }))

    fun nextHop(dest: DeviceId): DeviceId? = routes[dest]?.nextHop
    fun route(dest: DeviceId): Route? = routes[dest]
    fun isReachable(dest: DeviceId) = routes.containsKey(dest)

    /** Advance our own sequence number; call once per advertisement period. */
    fun tick(): Long {
        selfSeq += 2
        return selfSeq
    }

    /**
     * Build the advertisement for one neighbour. When [relayEnabled] is false (user setting or battery
     * policy), only ourselves is advertised, so nobody routes through this device.
     */
    fun advertFor(neighbor: DeviceId, selfName: String, relayEnabled: Boolean): List<RouteEntry> {
        val out = ArrayList<RouteEntry>()
        out += RouteEntry(self, selfSeq, 0, selfName)
        if (relayEnabled) {
            for (r in routes.values) {
                if (r.nextHop == neighbor || r.dest == neighbor) continue // split horizon
                if (r.hops >= ProtocolLimits.MAX_HOPS) continue
                out += RouteEntry(r.dest, r.seq, r.hops, r.name)
                if (out.size >= ProtocolLimits.MAX_ROUTES_PER_ADVERT) break
            }
        }
        for (w in withdrawn.values) {
            if (out.size >= ProtocolLimits.MAX_ROUTES_PER_ADVERT) break
            if (w.dest != neighbor) out += w
        }
        return out
    }

    /** Withdrawals are sent in one round of advertisements, then forgotten. */
    fun clearWithdrawn() = withdrawn.clear()

    /** Apply an advertisement received from a directly linked, trusted neighbour. Returns true if the table changed. */
    fun onAdvert(from: DeviceId, entries: List<RouteEntry>, now: Long): Boolean {
        var changed = false
        for (e in entries) {
            if (e.dest == self) continue
            val existing = routes[e.dest]
            if (e.broken) {
                if (existing != null && existing.nextHop == from && e.seq >= existing.seq) {
                    routes.remove(e.dest)
                    withdrawn[e.dest] = RouteEntry(e.dest, e.seq, RouteEntry.INFINITE, e.name)
                    changed = true
                }
                continue
            }
            // The neighbour itself is always 1 hop, even if it advertised an older sequence number.
            val hops = if (e.dest == from) 1 else e.hops + 1
            if (hops > ProtocolLimits.MAX_HOPS) continue
            val better = existing == null ||
                e.seq > existing.seq ||
                (e.seq == existing.seq && hops < existing.hops) ||
                (existing.nextHop == from && e.seq >= existing.seq) ||
                (e.dest == from && existing.hops > 1)
            if (better) {
                val r = Route(e.dest, from, hops, e.seq, e.name, now)
                if (existing == null || existing.nextHop != r.nextHop || existing.hops != r.hops || existing.name != r.name) changed = true
                routes[e.dest] = r
                withdrawn.remove(e.dest)
            }
        }
        return changed
    }

    /** A trusted neighbour linked directly. */
    fun addNeighbor(id: DeviceId, name: String, now: Long) {
        routes[id] = Route(id, id, 1, routes[id]?.seq ?: 0, name, now)
        withdrawn.remove(id)
    }

    /** A neighbour link went down: drop every route through it and schedule withdrawals. */
    fun removeNeighbor(id: DeviceId): List<DeviceId> {
        val lost = routes.values.filter { it.nextHop == id }.map { it.dest }
        for (d in lost) {
            val r = routes.remove(d)!!
            withdrawn[d] = RouteEntry(d, r.seq + 1, RouteEntry.INFINITE, r.name)
        }
        return lost
    }

    /** Drop routes not refreshed recently. Returns the destinations that became unreachable. */
    fun expire(now: Long, directNeighbors: Set<DeviceId>): List<DeviceId> {
        val stale = routes.values.filter { it.dest !in directNeighbors && now - it.updatedAt > routeTimeoutMs }.map { it.dest }
        for (d in stale) {
            val r = routes.remove(d)!!
            withdrawn[d] = RouteEntry(d, r.seq + 1, RouteEntry.INFINITE, r.name)
        }
        return stale
    }
}

/** Bounded, time-limited set of recently seen IDs for duplicate suppression. Thread-safe. */
class DedupeCache(private val capacity: Int = 4096, private val ttlMs: Long = 15 * 60_000L) {
    private val lock = Lock()
    private val seen = LinkedHashMap<DeviceId, Long>()

    /** Returns true if [id] was already seen (and is still within the TTL); otherwise records it. */
    fun checkAndAdd(id: DeviceId, now: Long): Boolean {
        return lock.withLock {
            val t = seen[id]
            if (t != null && now - t <= ttlMs) return true
            seen[id] = now
            if (seen.size > capacity) seen.remove(seen.keys.first())
            return false
        }
    }

    fun contains(id: DeviceId, now: Long): Boolean = lock.withLock { seen[id]?.let { now - it <= ttlMs } ?: false }
}
