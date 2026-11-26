package ai.paai.link.core.maps

import ai.paai.link.core.util.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ai.paai.link.core.util.IoDispatcher
import kotlin.concurrent.Volatile

/** Snapshot of the map download for the UI. [state]: IDLE, PLANNING, PLANNED, DOWNLOADING, FAILED, DONE. */
data class MapDownloadView(
    val state: String,
    val name: String = "",
    val tiles: Int = 0,
    val bytes: Long = 0,
    val maxZoom: Int = 0,
    val done: Long = 0,
    val total: Long = 0,
    val message: String = "",
)

/**
 * Offline map areas for the iPhone app (the Android app has an equivalent controller): list,
 * plan + download (needs internet once), import a .pmtiles file, delete.
 */
class MapAreaManager(dir: String, private val scope: CoroutineScope, private val onChange: () -> Unit) {
    val regions = MapRegions(dir)
    private val _list = MutableStateFlow<List<MapRegion>>(emptyList())
    val list: StateFlow<List<MapRegion>> = _list.asStateFlow()
    private val _download = MutableStateFlow(MapDownloadView("IDLE"))
    val download: StateFlow<MapDownloadView> = _download.asStateFlow()
    private var extractor: RegionExtractor? = null
    private var plan: RegionPlan? = null
    private var job: Job? = null
    @Volatile private var cancel = false

    init { refresh() }

    private fun set(v: MapDownloadView) { _download.value = v; onChange() }

    fun refresh() {
        scope.launch(IoDispatcher) { _list.value = regions.list(); onChange() }
    }

    fun planArea(name: String, lat: Double, lon: Double, radiusKm: Double) {
        if (job?.isActive == true) return
        job = scope.launch(IoDispatcher) {
            set(MapDownloadView("PLANNING", name))
            try {
                val ex = RegionExtractor(HttpRangeReader(MapRegions.latestBuildUrl()))
                val p = ex.plan(BoundingBox.around(lat, lon, radiusKm), MapRegions.maxZoomFor(radiusKm))
                extractor = ex
                plan = p
                set(MapDownloadView("PLANNED", name, p.tileCount, p.bytes, p.maxZoom))
            } catch (e: Exception) {
                set(MapDownloadView("FAILED", name, message = "Could not reach the map server. Downloading a map needs internet once. (${e.message ?: "error"})"))
            }
        }
    }

    fun startDownload() {
        val p = plan ?: return
        val ex = extractor ?: return
        val name = _download.value.name
        cancel = false
        job = scope.launch(IoDispatcher) {
            val path = regions.pathFor(name)
            try {
                ex.download(p, path, progress = { d, t -> set(MapDownloadView("DOWNLOADING", name, p.tileCount, p.bytes, p.maxZoom, d, t)) }, cancelled = { cancel })
                set(MapDownloadView("DONE", name, p.tileCount, Files.size(path), p.maxZoom, message = "Saved"))
            } catch (e: RegionExtractor.CancelledException) {
                set(MapDownloadView("IDLE"))
            } catch (e: Exception) {
                set(MapDownloadView("FAILED", name, message = "Download failed: ${e.message ?: "error"}"))
            } finally {
                plan = null
                extractor = null
                refresh()
            }
        }
    }

    fun cancelDownload() {
        cancel = true
        if (_download.value.state != "DOWNLOADING") { plan = null; set(MapDownloadView("IDLE")) }
    }

    /** Copies a picked .pmtiles file into the map folder. Returns a message for the user. */
    fun importFile(sourcePath: String, suggestedName: String): String {
        val head = Files.readRange(sourcePath, 0, Pmtiles.HEADER_SIZE) ?: return "Could not read the file"
        val h = runCatching { Pmtiles.readHeader(head) }.getOrElse { return "Not a PMTiles map file" }
        if (h.tileType != Pmtiles.TILE_MVT) return "Only vector map files are supported"
        val target = regions.pathFor(suggestedName)
        val total = Files.size(sourcePath)
        var off = 0L
        Files.delete("$target.part")
        while (off < total) {
            val chunk = Files.readRange(sourcePath, off, 1 shl 20) ?: break
            if (chunk.isEmpty()) break
            Files.append("$target.part", chunk)
            off += chunk.size
        }
        Files.rename("$target.part", target)
        refresh()
        return "Map imported"
    }

    fun delete(path: String) {
        Files.delete(path)
        refresh()
    }
}
