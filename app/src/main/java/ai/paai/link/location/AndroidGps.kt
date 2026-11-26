package ai.paai.link.location

import ai.paai.link.core.location.GeoFix
import ai.paai.link.core.node.LogBuffer
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * GPS through the platform LocationManager (no Google Play services), so it works with no
 * internet. Runs only while the user shares their location, sends an SOS, or looks at friends'
 * positions; the caller decides via [setWanted].
 */
class AndroidGps(private val context: Context, private val log: LogBuffer, private val onFix: (GeoFix) -> Unit) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var running = false
    private var lastGpsAt = 0L

    // All four methods are implemented: before Android 10 they were abstract, and relying on the
    // newer default methods crashes on those versions.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = feed(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    val gpsEnabled: Boolean get() = runCatching { lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true }.getOrDefault(false)

    fun setWanted(wanted: Boolean) = if (wanted) start() else stop()

    @SuppressLint("MissingPermission")
    private fun start() {
        val m = lm ?: return
        if (running || !hasPermission()) return
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { runCatching { m.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            log.w(TAG, "location is switched off in Android settings")
            return
        }
        running = true
        for (p in providers) runCatching { m.requestLocationUpdates(p, 3_000L, 3f, listener, Looper.getMainLooper()) }
        providers.mapNotNull { runCatching { m.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }?.let(::feed)
        log.i(TAG, "location on (${providers.joinToString()})")
    }

    private fun stop() {
        if (!running) return
        running = false
        runCatching { lm?.removeUpdates(listener) }
        log.i(TAG, "location off")
    }

    private fun feed(l: Location) {
        val now = System.currentTimeMillis()
        if (l.provider == LocationManager.GPS_PROVIDER) lastGpsAt = now
        // Prefer GPS: ignore coarser network fixes while GPS is delivering.
        else if (now - lastGpsAt < 15_000) return
        onFix(GeoFix(l.latitude, l.longitude, if (l.hasAccuracy()) l.accuracy else 50f, l.time))
    }

    private companion object { const val TAG = "gps" }
}
