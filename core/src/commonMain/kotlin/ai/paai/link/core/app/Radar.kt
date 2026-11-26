package ai.paai.link.core.app

/**
 * The "Nearby radar": where each phone is drawn around this one. Rings show how a phone is
 * reached, not a measured distance (phones share no location):
 * inner = linked directly, middle = reached through a relay, outer = nearby but not linked yet.
 */
enum class RadarRing(val radius: Float) { LINKED(0.36f), RELAYED(0.62f), NEARBY(0.86f) }

/** One phone on the radar. [angleDeg] is clockwise from 12 o'clock; [radius] is a fraction of the radar radius. */
data class RadarDot(
    val key: String,
    val name: String,
    val ring: RadarRing,
    val angleDeg: Float,
    val radius: Float,
    /** Paired device ID (hex) when the dot is a paired phone, else empty. */
    val peerId: String,
    /** Link waiting for the pairing code, else -1. */
    val pairingLinkId: Long,
)

data class RadarEntry(val key: String, val name: String, val ring: RadarRing, val peerId: String = "", val pairingLinkId: Long = -1)

object RadarLayout {
    /** At most this many dots per ring; the rest are counted in [overflow]. */
    const val MAX_PER_RING = 10
    private val ringOffsetDeg = mapOf(RadarRing.LINKED to 0f, RadarRing.RELAYED to 30f, RadarRing.NEARBY to 15f)

    /**
     * Spreads each ring's phones evenly. Order within a ring is by a hash of the key, so a phone
     * keeps its neighbours when others come and go, and both apps draw the same picture.
     */
    fun place(entries: List<RadarEntry>): List<RadarDot> {
        val out = ArrayList<RadarDot>()
        for (ring in RadarRing.entries) {
            val inRing = entries.filter { it.ring == ring }.distinctBy { it.key }
                .sortedWith(compareBy<RadarEntry> { stableHash(it.key) }.thenBy { it.key })
                .take(MAX_PER_RING)
            if (inRing.isEmpty()) continue
            val step = 360f / inRing.size
            val start = ringOffsetDeg.getValue(ring) + if (inRing.size == 1) 35f else 0f
            inRing.forEachIndexed { i, e ->
                val angle = (start + i * step) % 360f
                out += RadarDot(e.key, e.name, ring, angle, ring.radius, e.peerId, e.pairingLinkId)
            }
        }
        return out
    }

    /** How many entries did not fit on their ring. */
    fun overflow(entries: List<RadarEntry>): Int =
        RadarRing.entries.sumOf { r -> (entries.filter { it.ring == r }.distinctBy { it.key }.size - MAX_PER_RING).coerceAtLeast(0) }

    /** FNV-1a; identical on every platform (String.hashCode is too, but this is explicit). */
    private fun stableHash(s: String): Int {
        var h = 0x811c9dc5.toInt()
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toInt() and 0xff)
            h *= 0x01000193
        }
        return h and 0x7fffffff
    }
}
