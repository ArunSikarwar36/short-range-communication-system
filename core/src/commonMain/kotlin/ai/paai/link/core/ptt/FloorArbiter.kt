package ai.paai.link.core.ptt

import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.withLock
import ai.paai.link.core.util.DeviceId

/**
 * Push-to-talk floor control, run by the group host. Guarantees at most one talker at a time.
 *
 * Recovery: the holder must send a keepalive at least every [keepaliveTimeoutMs]; if it goes silent
 * (app killed, link lost, phone out of range) or holds the floor longer than [maxTalkMs], [tick]
 * revokes the floor, so a lost peer can never lock the channel.
 */
class FloorArbiter(val maxTalkMs: Long = 60_000, val keepaliveTimeoutMs: Long = 3_000) {
    private val lock = Lock()
    sealed class Decision {
        data class Granted(val floorSeq: Long) : Decision()
        data class Denied(val holder: DeviceId) : Decision()
    }

    var holder: DeviceId? = null
        private set
    var floorSeq: Long = 0
        private set
    private var holderSince = 0L
    private var lastKeepalive = 0L

    fun request(member: DeviceId, now: Long): Decision {
        return lock.withLock {
            val h = holder
            if (h != null && h != member) return Decision.Denied(h)
            if (h == null) {
                holder = member
                floorSeq++
                holderSince = now
            }
            lastKeepalive = now
            return Decision.Granted(floorSeq)
        }
    }

    /** Returns true if the floor was freed. Stale releases (old floorSeq) are ignored. */
    fun release(member: DeviceId, seq: Long): Boolean {
        return lock.withLock {
            if (holder != member || seq != floorSeq) return false
            holder = null
            return true
        }
    }

    fun keepalive(member: DeviceId, seq: Long, now: Long) {
        return lock.withLock {
            if (holder == member && seq == floorSeq) lastKeepalive = now
        }
    }

    /** Returns the member whose floor was revoked, if any. */
    fun tick(now: Long): DeviceId? {
        return lock.withLock {
            val h = holder ?: return null
            if (now - lastKeepalive > keepaliveTimeoutMs || now - holderSince > maxTalkMs) {
                holder = null
                return h
            }
            return null
        }
    }

    /** A member left or was removed. Returns true if it held the floor. */
    fun memberLeft(member: DeviceId): Boolean {
        return lock.withLock {
            if (holder != member) return false
            holder = null
            return true
        }
    }
}

/** What this device's push-to-talk UI shows. */
data class PttState(
    /** Current talker ([null] = channel free). */
    val holder: DeviceId? = null,
    val floorSeq: Long = 0,
    val requesting: Boolean = false,
    val talking: Boolean = false,
    /** Last request was denied because someone else was talking. */
    val denied: Boolean = false,
    val revoked: Boolean = false,
)
