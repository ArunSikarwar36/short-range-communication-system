package ai.paai.link.core.location

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A GPS position. [at] is wall-clock milliseconds. */
data class GeoFix(val lat: Double, val lon: Double, val accuracyM: Float, val at: Long) {
    val latE7: Int get() = Geo.toE7(lat)
    val lonE7: Int get() = Geo.toE7(lon)
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun toE7(deg: Double): Int = (deg * 1e7).roundToLong().toInt()
    fun fromE7(e7: Int): Double = e7 / 1e7

    private fun rad(d: Double) = d * PI / 180.0

    /** Great-circle (haversine) distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = rad(lat2 - lat1)
        val dLon = rad(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(rad(lat1)) * cos(rad(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north (0..360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val y = sin(rad(lon2 - lon1)) * cos(rad(lat2))
        val x = cos(rad(lat1)) * sin(rad(lat2)) - sin(rad(lat1)) * cos(rad(lat2)) * cos(rad(lon2 - lon1))
        val deg = atan2(y, x) * 180.0 / PI
        return (deg + 360.0) % 360.0
    }

    private val compassPoints = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Eight-point compass direction, e.g. "NE". */
    fun compass(bearingDeg: Double): String = compassPoints[(((bearingDeg % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]

    /** "35 m", "850 m", "1.2 km", "14 km". */
    fun formatDistance(m: Double): String = when {
        m < 1_000 -> "${(m / 5).roundToInt() * 5} m"
        m < 10_000 -> "${((m / 100).roundToInt() / 10.0)} km"
        else -> "${(m / 1_000).roundToInt()} km"
    }
}
