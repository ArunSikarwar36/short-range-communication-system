package ai.paai.link.core.media

import ai.paai.link.core.util.SortedLongMap
import ai.paai.link.core.util.Lock
import ai.paai.link.core.util.withLock
import kotlin.math.abs

/**
 * Per-stream adaptive jitter buffer. Bounded in both packets and delay.
 *
 * - Playout starts once [targetDelayMs] of audio is buffered.
 * - Each [poll] (called once per frame period by the playout loop) yields the next frame in
 *   sequence, or a gap that the decoder conceals.
 * - Packets arriving after their slot was played are counted as late and dropped.
 * - If the buffer grows beyond target + [maxExtraMs], the oldest frames are skipped to bound latency.
 * - After a long run of missing frames with an empty buffer, playout pauses and re-buffers.
 * - The target delay follows the RFC 3550 inter-arrival jitter estimate.
 */
class JitterBuffer(
    private val frameMs: Int = 20,
    private val minDelayMs: Int = 40,
    private val maxDelayMs: Int = 200,
    private val maxExtraMs: Int = 120,
    private val capacity: Int = 64,
) {
    private val lock = Lock()
    sealed class Frame {
        class Packet(val payload: ByteArray, val nextAvailable: ByteArray?) : Frame()
        /** Slot missing: run packet-loss concealment. */
        data object Missing : Frame()
        /** Not playing (buffering, idle, or re-buffering). */
        data object Empty : Frame()
    }

    private val packets = SortedLongMap<ByteArray>()
    private var playing = false
    private var nextSeq = 0L
    private var missingRun = 0
    private var jitter = 0.0
    private var lastTransit: Double? = null
    private var highestSeen = -1L

    var received = 0L; private set
    var lost = 0L; private set
    var late = 0L; private set
    var duplicates = 0L; private set
    var skipped = 0L; private set

    val jitterMs: Int get() = jitter.toInt()
    val targetDelayMs: Int get() = (2 * frameMs + 3 * jitter).toInt().coerceIn(minDelayMs, maxDelayMs)
    val bufferedMs: Int get() = lock.withLock { packets.size * frameMs }
    val isPlaying: Boolean get() = lock.withLock { playing }

    /** Loss ratio in permille over the lifetime of the stream. */
    val lossPermille: Int get() = if (received + lost == 0L) 0 else (lost * 1000 / (received + lost)).toInt()

    /**
     * @param seq packet sequence number
     * @param mediaTimeMs sender media clock converted to milliseconds
     * @param arrivalMs local receive time
     */
    fun put(seq: Long, mediaTimeMs: Long, arrivalMs: Long, payload: ByteArray) {
        return lock.withLock {
            val transit = (arrivalMs - mediaTimeMs).toDouble()
            lastTransit?.let { jitter += (abs(transit - it) - jitter) / 16.0 }
            lastTransit = transit
            if (playing && seq < nextSeq) { late++; return }
            if (packets.containsKey(seq)) {
                duplicates++
                return
            }
            received++
            if (seq > highestSeen) highestSeen = seq
            packets[seq] = payload
            while (packets.size > capacity) { packets.pollFirstEntry(); skipped++ }
        }
    }

    fun poll(): Frame {
        return lock.withLock {
            if (!playing) {
                if (packets.isEmpty() || packets.size * frameMs < targetDelayMs) return Frame.Empty
                playing = true
                nextSeq = packets.firstKey()
                missingRun = 0
            }
            // Bound latency: drop the oldest audio if we are far behind.
            while (packets.size * frameMs > targetDelayMs + maxExtraMs) {
                val first = packets.pollFirstEntry() ?: break
                nextSeq = first.key + 1
                skipped++
            }
            val p = packets.remove(nextSeq)
            nextSeq++
            if (p != null) {
                missingRun = 0
                return Frame.Packet(p, packets[nextSeq])
            }
            missingRun++
            if (packets.isEmpty() && missingRun * frameMs >= RESET_AFTER_MS) {
                // Talk spurt ended or the stream stalled; wait for a fresh buffer before playing again.
                playing = false
                // Frames counted as lost during the stall are not real losses; undo them.
                lost -= (missingRun - 1).coerceAtLeast(0)
                missingRun = 0
                return Frame.Empty
            }
            lost++
            return Frame.Missing
        }
    }

    /** Drops everything buffered (DJ Mode pause / next song); playback re-buffers from the next packet. */
    fun clear() {
        lock.withLock {
            packets.clear()
            playing = false
            missingRun = 0
        }
    }

    companion object {
        const val RESET_AFTER_MS = 200
    }
}

/**
 * Chooses the encoder bitrate from receiver reports: steps down quickly on loss, steps up slowly
 * after a sustained period of good conditions.
 */
class BitrateController(private val steps: List<Int> = listOf(32_000, 24_000, 16_000, 12_000, 8_000), initial: Int = 24_000) {
    private val lock = Lock()
    private var index = steps.indexOf(initial).takeIf { it >= 0 } ?: 1
    private var goodSince: Long? = null
    private val reports = HashMap<Any, Pair<Int, Long>>()

    val bitrate: Int get() = steps[index]

    /** Record a report and return the new bitrate if it changed, else null. */
    fun onReport(reporter: Any, lossPermille: Int, now: Long): Int? {
        return lock.withLock {
            reports[reporter] = lossPermille to now
            reports.entries.removeAll { now - it.value.second > 8_000 }
            val worst = reports.values.maxOf { it.first }
            val before = index
            when {
                worst > 80 -> { index = minOf(index + 1, steps.lastIndex); goodSince = null }
                worst < 20 -> {
                    val since = goodSince ?: now.also { goodSince = it }
                    if (now - since >= 10_000) { index = maxOf(index - 1, 0); goodSince = now }
                }
                else -> goodSince = null
            }
            return if (index != before) steps[index] else null
        }
    }
}
