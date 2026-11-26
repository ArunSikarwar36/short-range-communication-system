package ai.paai.link.core

import ai.paai.link.core.maps.BoundingBox
import ai.paai.link.core.maps.HttpRangeReader
import ai.paai.link.core.maps.Pmtiles
import ai.paai.link.core.maps.RegionExtractor
import ai.paai.link.core.util.Files

/**
 * Developer tool: download one area from a Protomaps build with the app's own extractor.
 * gradlew :core:mapExtract -Pmap.lat=28.6139 -Pmap.lon=77.2090 -Pmap.km=5 -Pmap.zoom=15 -Pmap.out=D:/android-build/maps/delhi.pmtiles
 */
fun main() {
    val lat = System.getProperty("map.lat", "28.6139").toDouble()
    val lon = System.getProperty("map.lon", "77.2090").toDouble()
    val km = System.getProperty("map.km", "5").toDouble()
    val zoom = System.getProperty("map.zoom", "15").toInt()
    val out = System.getProperty("map.out", "map.pmtiles")
    val url = System.getProperty("map.url", "https://build.protomaps.com/20261126.pmtiles")
    val t0 = System.currentTimeMillis()
    val ex = RegionExtractor(HttpRangeReader(url))
    val plan = ex.plan(BoundingBox.around(lat, lon, km), zoom)
    println("plan: ${plan.tileCount} tiles, ${plan.bytes / 1024} KiB, zoom ${plan.minZoom}-${plan.maxZoom} (${System.currentTimeMillis() - t0} ms)")
    java.io.File(out).parentFile?.mkdirs()
    var lastPct = -1L
    ex.download(plan, out, progress = { d, t -> val pct = if (t == 0L) 100 else d * 100 / t; if (pct / 10 != lastPct / 10) { println("  $pct%"); lastPct = pct } })
    val h = Pmtiles.readHeader(Files.read(out)!!.copyOf(127))
    println("wrote $out: ${Files.size(out) / 1024} KiB, ${h.addressedTiles} tiles, zoom ${h.minZoom}-${h.maxZoom}, ${System.currentTimeMillis() - t0} ms")
}
