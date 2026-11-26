package ai.paai.link.core.maps

import ai.paai.link.core.util.Files

/** A downloaded or imported map area on this phone. */
data class MapRegion(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2

    fun contains(lat: Double, lon: Double) = lat in minLat..maxLat && lon in minLon..maxLon
}

/**
 * Map areas stored as .pmtiles files in [dir]. Shared by Android and iOS. Downloading needs
 * internet once (HTTP range requests to a Protomaps build, the documented way to extract areas);
 * using the maps afterwards needs none.
 */
class MapRegions(private val dir: String) {
    init { Files.mkdirs(dir) }

    fun list(): List<MapRegion> = Files.list(dir).filter { it.endsWith(".pmtiles") }.mapNotNull { info("$dir/$it") }

    fun info(path: String): MapRegion? {
        val head = readHead(path) ?: return null
        val h = runCatching { Pmtiles.readHeader(head) }.getOrNull() ?: return null
        val name = path.substringAfterLast('/').removeSuffix(".pmtiles")
        return MapRegion(name, path, Files.size(path), h.minZoom, h.maxZoom, h.minLonE7 / 1e7, h.minLatE7 / 1e7, h.maxLonE7 / 1e7, h.maxLatE7 / 1e7)
    }

    private fun readHead(path: String): ByteArray? = Files.readRange(path, 0, Pmtiles.HEADER_SIZE)?.takeIf { it.size == Pmtiles.HEADER_SIZE }

    fun delete(region: MapRegion) = Files.delete(region.path)

    /** Safe file name for a new area. */
    fun pathFor(name: String): String {
        val clean = name.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "").replace(' ', '_').take(48).ifBlank { "map" }
        var candidate = "$dir/$clean.pmtiles"
        var n = 2
        while (Files.size(candidate) >= 0) candidate = "$dir/${clean}_${n++}.pmtiles"
        return candidate
    }

    /** Best area to show for a position: the most detailed one that contains it, else the largest. */
    fun bestFor(lat: Double?, lon: Double?): MapRegion? {
        val all = list()
        if (lat != null && lon != null) all.filter { it.contains(lat, lon) }.maxByOrNull { it.maxZoom * 1e9 - it.sizeBytes }?.let { return it }
        return all.maxByOrNull { it.sizeBytes }
    }

    companion object {
        const val BUILDS_INDEX = "https://build-metadata.protomaps.dev/builds.json"
        const val BUILDS_BASE = "https://build.protomaps.com/"

        /** URL of the newest Protomaps daily build (world basemap, PMTiles). Needs internet. */
        fun latestBuildUrl(): String {
            val json = httpGetText(BUILDS_INDEX)
            val newest = Regex("\"key\"\\s*:\\s*\"(\\d{8})\\.pmtiles\"").findAll(json).map { it.groupValues[1] }.maxOrNull()
                ?: throw IllegalStateException("no map builds listed")
            return "$BUILDS_BASE$newest.pmtiles"
        }

        /** Detail levels offered for an area radius: city detail (15) for small areas, less for large ones. */
        fun maxZoomFor(radiusKm: Double): Int = when {
            radiusKm <= 8 -> 15
            radiusKm <= 20 -> 14
            radiusKm <= 60 -> 13
            else -> 11
        }
    }
}

/** Plain HTTP GET of a small text document (the builds index). */
expect fun httpGetText(url: String): String
