package ai.paai.link.core.maps

import ai.paai.link.core.util.Files
import kotlin.math.max
import kotlin.math.min

/** A rectangular map area in degrees. */
data class BoundingBox(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    init {
        require(minLon < maxLon && minLat < maxLat) { "empty area" }
        require(minLon >= -180 && maxLon <= 180 && minLat >= -85.06 && maxLat <= 85.06) { "area out of range" }
    }

    companion object {
        /** Square area of about [radiusKm] around a point. */
        fun around(lat: Double, lon: Double, radiusKm: Double): BoundingBox {
            val dLat = radiusKm / 111.32
            val dLon = radiusKm / (111.32 * kotlin.math.cos(lat * kotlin.math.PI / 180).coerceAtLeast(0.05))
            return BoundingBox(
                (lon - dLon).coerceAtLeast(-180.0), (lat - dLat).coerceAtLeast(-85.0),
                (lon + dLon).coerceAtMost(180.0), (lat + dLat).coerceAtMost(85.0),
            )
        }
    }
}

/** What a download will fetch; shown to the user before downloading. */
class RegionPlan internal constructor(
    val area: BoundingBox,
    val minZoom: Int,
    val maxZoom: Int,
    /** Tiles inside the area (including repeated empty / sea tiles). */
    val tileCount: Int,
    /** Bytes that will be downloaded and stored (unique tile data). */
    val bytes: Long,
    internal val source: Pmtiles.Header,
    internal val tiles: List<Pair<Long, Pmtiles.Entry>>,
)

/**
 * Downloads one area of a PMTiles world map (e.g. a Protomaps daily build) into a small local
 * PMTiles file, the way `pmtiles extract` does: read the directories, keep only the tiles inside the
 * area, fetch their bytes with merged range requests, and write a new clustered archive.
 */
class RegionExtractor(private val reader: RangeReader) {
    private var header: Pmtiles.Header? = null
    private var root: List<Pmtiles.Entry> = emptyList()
    private val leaves = HashMap<Long, List<Pmtiles.Entry>>()

    private fun open(): Pmtiles.Header {
        header?.let { return it }
        val start = reader.read(0, Pmtiles.ROOT_LIMIT)
        val h = Pmtiles.readHeader(start)
        if (h.internalCompression != Pmtiles.COMPRESSION_GZIP && h.internalCompression != Pmtiles.COMPRESSION_NONE) {
            throw Pmtiles.FormatException("unsupported directory compression ${h.internalCompression}")
        }
        val rootBytes = if (h.rootOffset + h.rootLength <= start.size) {
            start.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())
        } else {
            reader.read(h.rootOffset, h.rootLength.toInt())
        }
        root = Pmtiles.decodeDirectory(inflate(h, rootBytes))
        header = h
        return h
    }

    private fun inflate(h: Pmtiles.Header, b: ByteArray) = if (h.internalCompression == Pmtiles.COMPRESSION_GZIP) Gzip.decompress(b) else b

    private fun locate(h: Pmtiles.Header, tileId: Long): Pmtiles.Entry? {
        var dir = root
        repeat(4) {
            val e = Pmtiles.find(dir, tileId) ?: return null
            if (e.runLength > 0) return e
            val leafStart = h.leafOffset + e.offset
            dir = leaves.getOrPut(leafStart) { Pmtiles.decodeDirectory(inflate(h, reader.read(leafStart, e.length))) }
        }
        return null
    }

    /** Lists the tiles of [area] for zoom levels 0..[maxZoom] (capped at the source's maximum). */
    fun plan(area: BoundingBox, maxZoom: Int, minZoom: Int = 0): RegionPlan {
        val h = open()
        val zMax = min(maxZoom, h.maxZoom)
        val zMin = max(minZoom, h.minZoom)
        val found = ArrayList<Pair<Long, Pmtiles.Entry>>()
        for (z in zMin..zMax) {
            val x0 = Pmtiles.lonToTileX(area.minLon, z); val x1 = Pmtiles.lonToTileX(area.maxLon, z)
            val y0 = Pmtiles.latToTileY(area.maxLat, z); val y1 = Pmtiles.latToTileY(area.minLat, z)
            for (x in x0..x1) for (y in y0..y1) {
                val id = Pmtiles.zxyToTileId(z, x, y)
                locate(h, id)?.let { found += id to it }
            }
        }
        found.sortBy { it.first }
        val unique = found.map { it.second.offset to it.second.length }.distinct()
        return RegionPlan(area, zMin, zMax, found.size, unique.sumOf { it.second.toLong() }, h, found)
    }

    /**
     * Writes the planned area to [outPath] (via a temporary file, so a cancelled download leaves no
     * broken map). [progress] gets (bytes done, bytes total); return true from [cancelled] to stop.
     */
    fun download(plan: RegionPlan, outPath: String, progress: (Long, Long) -> Unit = { _, _ -> }, cancelled: () -> Boolean = { false }) {
        val src = plan.source
        // New layout: each distinct tile content once, in the order its first tile appears.
        val newOffset = LinkedHashMap<Long, Long>() // source offset -> new offset
        val contents = ArrayList<Pmtiles.Entry>() // source entries of unique contents, in write order
        var dataLength = 0L
        for ((_, e) in plan.tiles) {
            if (e.offset !in newOffset) {
                newOffset[e.offset] = dataLength
                contents += e
                dataLength += e.length
            }
        }
        val entries = ArrayList<Pmtiles.Entry>()
        for ((id, e) in plan.tiles) {
            val off = newOffset.getValue(e.offset)
            val last = entries.lastOrNull()
            if (last != null && last.offset == off && last.tileId + last.runLength == id) {
                entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            } else {
                entries += Pmtiles.Entry(id, off, e.length, 1)
            }
        }
        val (rootDir, leafDirs) = buildDirectories(entries)
        val metadata = if (src.metadataLength > 0) reader.read(src.metadataOffset, src.metadataLength.toInt()) else ByteArray(0)

        val area = plan.area
        val rootOffset = Pmtiles.HEADER_SIZE.toLong()
        val metaOffset = rootOffset + rootDir.size
        val leafOffset = metaOffset + metadata.size
        val dataOffset = leafOffset + leafDirs.size
        val header = Pmtiles.Header(
            rootOffset, rootDir.size.toLong(), metaOffset, metadata.size.toLong(), leafOffset, leafDirs.size.toLong(),
            dataOffset, dataLength, plan.tileCount.toLong(), entries.size.toLong(), contents.size.toLong(), clustered = true,
            internalCompression = Pmtiles.COMPRESSION_GZIP, tileCompression = src.tileCompression, tileType = src.tileType,
            minZoom = plan.minZoom, maxZoom = plan.maxZoom,
            minLonE7 = e7(area.minLon), minLatE7 = e7(area.minLat), maxLonE7 = e7(area.maxLon), maxLatE7 = e7(area.maxLat),
            centerZoom = min(plan.maxZoom, 13), centerLonE7 = e7((area.minLon + area.maxLon) / 2), centerLatE7 = e7((area.minLat + area.maxLat) / 2),
        )

        val tmp = "$outPath.part"
        Files.delete(tmp)
        Files.writeAtomic(tmp, Pmtiles.writeHeader(header) + rootDir + metadata + leafDirs)
        var done = 0L
        progress(0, dataLength)
        var i = 0
        while (i < contents.size) {
            if (cancelled()) { Files.delete(tmp); throw CancelledException() }
            // Batch up to ~4 MB of tiles; fetch each run of nearby source bytes in one request.
            var j = i
            var batchBytes = 0L
            while (j < contents.size && (j == i || batchBytes + contents[j].length <= BATCH_BYTES)) { batchBytes += contents[j].length; j++ }
            val batch = contents.subList(i, j)
            val fetched = HashMap<Long, ByteArray>()
            val bySource = batch.sortedBy { it.offset }
            var k = 0
            while (k < bySource.size) {
                var end = k
                var rangeEnd = bySource[k].offset + bySource[k].length
                while (end + 1 < bySource.size && bySource[end + 1].offset - rangeEnd <= GAP_BYTES) {
                    end++
                    rangeEnd = max(rangeEnd, bySource[end].offset + bySource[end].length)
                }
                val start = bySource[k].offset
                val bytes = reader.read(src.dataOffset + start, (rangeEnd - start).toInt())
                for (m in k..end) {
                    val e = bySource[m]
                    val from = (e.offset - start).toInt()
                    if (from + e.length > bytes.size) throw Pmtiles.FormatException("short read from the map server")
                    fetched[e.offset] = bytes.copyOfRange(from, from + e.length)
                }
                k = end + 1
            }
            val out = ai.paai.link.core.util.GrowableBytes(batchBytes.toInt() + 16)
            for (e in batch) out.write(fetched.getValue(e.offset))
            Files.append(tmp, out.toByteArray())
            done += batchBytes
            progress(done, dataLength)
            i = j
        }
        Files.rename(tmp, outPath)
    }

    class CancelledException : Exception("download cancelled")

    private fun e7(d: Double) = kotlin.math.round(d * 1e7).toInt()

    companion object {
        private const val BATCH_BYTES = 4L * 1024 * 1024
        private const val GAP_BYTES = 32L * 1024

        /** Root directory (and leaf directories when needed) so that header + root fit in 16 KiB. */
        fun buildDirectories(entries: List<Pmtiles.Entry>): Pair<ByteArray, ByteArray> {
            val whole = Gzip.compress(Pmtiles.encodeDirectory(entries))
            if (whole.size + Pmtiles.HEADER_SIZE <= Pmtiles.ROOT_LIMIT) return whole to ByteArray(0)
            var perLeaf = 4096
            while (true) {
                val leafBytes = ai.paai.link.core.util.GrowableBytes(whole.size + 64)
                val rootEntries = ArrayList<Pmtiles.Entry>()
                var offset = 0L
                for (chunk in entries.chunked(perLeaf)) {
                    val leaf = Gzip.compress(Pmtiles.encodeDirectory(chunk))
                    rootEntries += Pmtiles.Entry(chunk.first().tileId, offset, leaf.size, 0)
                    leafBytes.write(leaf)
                    offset += leaf.size
                }
                val root = Gzip.compress(Pmtiles.encodeDirectory(rootEntries))
                if (root.size + Pmtiles.HEADER_SIZE <= Pmtiles.ROOT_LIMIT) return root to leafBytes.toByteArray()
                perLeaf *= 2
            }
        }
    }
}
