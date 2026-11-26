package ai.paai.link.maps

import ai.paai.link.core.maps.BoundingBox
import ai.paai.link.core.maps.HttpRangeReader
import ai.paai.link.core.maps.MapRegion
import ai.paai.link.core.maps.MapRegions
import ai.paai.link.core.maps.Pmtiles
import ai.paai.link.core.maps.RegionExtractor
import ai.paai.link.core.maps.RegionPlan
import ai.paai.link.core.node.LogBuffer
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Progress of the one map download that can run at a time. */
sealed class MapDownload {
    data object Idle : MapDownload()
    data class Planning(val name: String) : MapDownload()
    data class Planned(val name: String, val tiles: Int, val bytes: Long, val maxZoom: Int) : MapDownload()
    data class Downloading(val name: String, val done: Long, val total: Long) : MapDownload()
    data class Failed(val message: String) : MapDownload()
    data class Done(val region: MapRegion) : MapDownload()
}

/** Offline map areas on this phone: list, download (needs internet once), import, delete. */
class MapsController(private val context: Context, private val log: LogBuffer, private val scope: CoroutineScope) {
    val regions = MapRegions(File(context.filesDir, "maps").absolutePath)
    private val _list = MutableStateFlow<List<MapRegion>>(emptyList())
    val list: StateFlow<List<MapRegion>> = _list.asStateFlow()
    private val _download = MutableStateFlow<MapDownload>(MapDownload.Idle)
    val download: StateFlow<MapDownload> = _download.asStateFlow()

    private var extractor: RegionExtractor? = null
    private var plan: RegionPlan? = null
    private var job: Job? = null
    @Volatile private var cancel = false

    init { refresh() }

    fun refresh() { scope.launch(Dispatchers.IO) { _list.value = regions.list() } }

    /** Step 1: look up the newest world map build and size the area (small requests only). */
    fun planArea(name: String, lat: Double, lon: Double, radiusKm: Double) {
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            _download.value = MapDownload.Planning(name)
            try {
                val url = MapRegions.latestBuildUrl()
                val ex = RegionExtractor(HttpRangeReader(url))
                val p = ex.plan(BoundingBox.around(lat, lon, radiusKm), MapRegions.maxZoomFor(radiusKm))
                extractor = ex
                plan = p
                _download.value = MapDownload.Planned(name, p.tileCount, p.bytes, p.maxZoom)
                log.i(TAG, "map area '$name': ${p.tileCount} tiles, ${p.bytes / 1024} KiB")
            } catch (e: Exception) {
                _download.value = MapDownload.Failed("Could not reach the map server. Downloading a map needs internet once. (${e.message ?: e.javaClass.simpleName})")
            }
        }
    }

    /** Step 2: download the planned area. */
    fun startDownload() {
        val p = plan ?: return
        val ex = extractor ?: return
        val name = (_download.value as? MapDownload.Planned)?.name ?: "map"
        cancel = false
        job = scope.launch(Dispatchers.IO) {
            val path = regions.pathFor(name)
            try {
                ex.download(p, path, progress = { d, t -> _download.value = MapDownload.Downloading(name, d, t) }, cancelled = { cancel })
                val region = regions.info(path) ?: error("downloaded file is not readable")
                _download.value = MapDownload.Done(region)
                log.i(TAG, "map area '$name' saved (${region.sizeBytes / 1024} KiB)")
            } catch (e: RegionExtractor.CancelledException) {
                _download.value = MapDownload.Idle
            } catch (e: Exception) {
                _download.value = MapDownload.Failed("Download failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                plan = null
                extractor = null
                refresh()
            }
        }
    }

    fun cancelDownload() {
        cancel = true
        if (_download.value is MapDownload.Planned || _download.value is MapDownload.Failed || _download.value is MapDownload.Done) {
            _download.value = MapDownload.Idle
            plan = null
        }
    }

    /** Copies a .pmtiles file the user picked (e.g. received from a friend) into the app's map folder. */
    fun import(uri: Uri, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val name = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.ifBlank { null } ?: "Imported map"
            val path = regions.pathFor(name)
            val tmp = File("$path.part")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    ?: error("could not open the file")
                val head = ByteArray(Pmtiles.HEADER_SIZE)
                tmp.inputStream().use { input ->
                    var n = 0
                    while (n < head.size) {
                        val r = input.read(head, n, head.size - n)
                        if (r < 0) break
                        n += r
                    }
                }
                val h = Pmtiles.readHeader(head)
                if (h.tileType != Pmtiles.TILE_MVT) error("only vector map files (MVT) are supported")
                tmp.renameTo(File(path))
                refresh()
                onResult("Map '$name' imported")
            } catch (e: Exception) {
                tmp.delete()
                onResult("Not a usable map file: ${e.message}")
            }
        }
    }

    fun delete(region: MapRegion) {
        scope.launch(Dispatchers.IO) {
            regions.delete(region)
            refresh()
        }
    }

    private companion object { const val TAG = "maps" }
}
