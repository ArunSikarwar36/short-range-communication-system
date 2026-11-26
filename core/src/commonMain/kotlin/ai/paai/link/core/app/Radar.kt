package ai.paai.link.core.app

/**
 * The "Nearby radar": where each phone is drawn around this one. Rings show how a phone is
 * reached, not a measured distance (phones share no location):
 * inner = linked directly, middle = reached through a relay, outer = nearby but not linked yet.
 */
enum class RadarRing(val radius: Float) { LINKED(0.46f), RELAYED(0.68f), NEARBY(0.88f) }

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
    /** GPS distance in metres when both phones share a position (the dot then sits at its real bearing), else -1. */
    val distanceM: Double = -1.0,
)

/**
 * [bearingDeg] / [distanceM] (from GPS) place the dot at its real direction and a log-scaled
 * distance; without them the dot is spread evenly on its ring.
 */
data class RadarEntry(
    val key: String,
    val name: String,
    val ring: RadarRing,
    val peerId: String = "",
    val pairingLinkId: Long = -1,
    val bearingDeg: Double? = null,
    val distanceM: Double? = null,
)

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
        val unique = entries.distinctBy { it.key }
        for (e in unique) {
            val b = e.bearingDeg ?: continue
            val d = e.distanceM ?: continue
            out += RadarDot(e.key, e.name, e.ring, (((b % 360) + 360) % 360).toFloat(), geoRadius(d), e.peerId, e.pairingLinkId, d)
        }
        for (ring in RadarRing.entries) {
            val inRing = unique.filter { it.ring == ring && (it.bearingDeg == null || it.distanceM == null) }
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

    /** Radar edge in metres for GPS placement. */
    const val GEO_RANGE_M = 5_000.0

    /** Log scale: 0 m at the centre's edge (0.22), 5 km and beyond at 0.94. */
    fun geoRadius(distanceM: Double): Float {
        val f = kotlin.math.ln(1 + distanceM.coerceAtLeast(0.0) / 10) / kotlin.math.ln(1 + GEO_RANGE_M / 10)
        return (0.22 + 0.72 * f.coerceIn(0.0, 1.0)).toFloat()
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
