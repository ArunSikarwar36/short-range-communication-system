package ai.paai.link.core

import ai.paai.link.core.maps.BoundingBox
import ai.paai.link.core.maps.ByteArrayRangeReader
import ai.paai.link.core.maps.Gzip
import ai.paai.link.core.maps.Pmtiles
import ai.paai.link.core.maps.RegionExtractor
import ai.paai.link.core.util.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PmtilesTest {
    @Test
    fun hilbertTileIdsMatchTheSpec() {
        assertEquals(0, Pmtiles.zxyToTileId(0, 0, 0))
        // Zoom 1 follows the Hilbert curve: (0,0) (0,1) (1,1) (1,0).
        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(Pmtiles.zxyToTileId(1, 0, 0), Pmtiles.zxyToTileId(1, 0, 1), Pmtiles.zxyToTileId(1, 1, 1), Pmtiles.zxyToTileId(1, 1, 0)))
        assertEquals(5, Pmtiles.zxyToTileId(2, 0, 0))
        assertEquals(20, Pmtiles.zxyToTileId(2, 3, 0))
        // Every tile of a zoom level gets a distinct ID inside that level's range.
        for (z in 0..5) {
            val n = 1 shl z
            val ids = (0 until n).flatMap { x -> (0 until n).map { y -> Pmtiles.zxyToTileId(z, x, y) } }
            val first = (0 until z).sumOf { 1L shl (2 * it) }
            assertEquals((first until first + n.toLong() * n).toSet(), ids.toSet(), "zoom $z")
        }
        assertFailsWith<IllegalArgumentException> { Pmtiles.zxyToTileId(2, 4, 0) }
    }

    @Test
    fun directoryAndHeaderRoundTrip() {
        val entries = listOf(Pmtiles.Entry(0, 0, 100, 1), Pmtiles.Entry(1, 100, 50, 3), Pmtiles.Entry(9, 0, 100, 1), Pmtiles.Entry(70_000, 150, 12, 0))
        assertEquals(entries, Pmtiles.decodeDirectory(Pmtiles.encodeDirectory(entries)))
        assertEquals(entries[1], Pmtiles.find(entries, 3))
        assertNull(Pmtiles.find(entries, 5))
        assertEquals(entries[3], Pmtiles.find(entries, 1_000_000), "leaf entry covers higher IDs")
        val data = Random(1).nextBytes(5000)
        assertContentEquals(data, Gzip.decompress(Gzip.compress(data)))
        assertEquals(1, Pmtiles.lonToTileX(0.0, 1)); assertEquals(0, Pmtiles.latToTileY(10.0, 1)); assertEquals(1, Pmtiles.latToTileY(-10.0, 1))
    }

    /** Builds a source archive with leaf directories, repeated tiles and run lengths. */
    private fun sourceArchive(maxZoom: Int): Pair<ByteArray, Map<Long, ByteArray>> {
        val rnd = Random(42)
        val sea = byteArrayOf(9, 9, 9)
        val tiles = LinkedHashMap<Long, ByteArray>()
        for (z in 0..maxZoom) {
            val n = 1 shl z
            for (x in 0 until n) for (y in 0 until n) {
                tiles[Pmtiles.zxyToTileId(z, x, y)] = if ((x + y) % 3 == 0) sea else rnd.nextBytes(20 + rnd.nextInt(60))
            }
        }
        val ids = tiles.keys.sorted()
        val offsets = HashMap<Int, Long>() // identity of content -> offset
        val data = ai.paai.link.core.util.GrowableBytes()
        val entries = ArrayList<Pmtiles.Entry>()
        var off = 0L
        for (id in ids) {
            val c = tiles.getValue(id)
            val o = if (c === sea) offsets.getOrPut(-1) { val at = off; data.write(c); off += c.size; at } else { val at = off; data.write(c); off += c.size; at }
            val last = entries.lastOrNull()
            if (last != null && last.offset == o && last.tileId + last.runLength == id) entries[entries.size - 1] = last.copy(runLength = last.runLength + 1)
            else entries += Pmtiles.Entry(id, o, c.size, 1)
        }
        // Force leaf directories by using small leaves.
        val leafBytes = ai.paai.link.core.util.GrowableBytes()
        val rootEntries = ArrayList<Pmtiles.Entry>()
        var lo = 0L
        for (chunk in entries.chunked(64)) {
            val leaf = Gzip.compress(Pmtiles.encodeDirectory(chunk))
            rootEntries += Pmtiles.Entry(chunk.first().tileId, lo, leaf.size, 0)
            leafBytes.write(leaf); lo += leaf.size
        }
        val root = Gzip.compress(Pmtiles.encodeDirectory(rootEntries))
        val meta = Gzip.compress("""{"name":"test"}""".encodeToByteArray())
        val leaves = leafBytes.toByteArray()
        val dataBytes = data.toByteArray()
        val rootOff = 127L; val metaOff = rootOff + root.size; val leafOff = metaOff + meta.size; val dataOff = leafOff + leaves.size
        val h = Pmtiles.Header(
            rootOff, root.size.toLong(), metaOff, meta.size.toLong(), leafOff, leaves.size.toLong(), dataOff, dataBytes.size.toLong(),
            ids.size.toLong(), entries.size.toLong(), 0, true, 2, 2, 1, 0, maxZoom, -1_800_000_000, -850_000_000, 1_800_000_000, 850_000_000, 0, 0, 0,
        )
        return (Pmtiles.writeHeader(h) + root + meta + leaves + dataBytes) to tiles
    }

    private fun readTile(archive: ByteArray, id: Long): ByteArray? {
        val h = Pmtiles.readHeader(archive)
        var dir = Pmtiles.decodeDirectory(Gzip.decompress(archive.copyOfRange(h.rootOffset.toInt(), (h.rootOffset + h.rootLength).toInt())))
        while (true) {
            val e = Pmtiles.find(dir, id) ?: return null
            if (e.runLength > 0) return archive.copyOfRange((h.dataOffset + e.offset).toInt(), (h.dataOffset + e.offset + e.length).toInt())
            val s = (h.leafOffset + e.offset).toInt()
            dir = Pmtiles.decodeDirectory(Gzip.decompress(archive.copyOfRange(s, s + e.length)))
        }
    }

    @Test
    fun extractingAnAreaKeepsExactlyItsTiles() {
        val (source, tiles) = sourceArchive(maxZoom = 6)
        val reader = ByteArrayRangeReader(source)
        val ex = RegionExtractor(reader)
        val area = BoundingBox(70.0, 8.0, 90.0, 30.0) // roughly India
        val plan = ex.plan(area, maxZoom = 6)
        assertEquals(0, plan.minZoom); assertEquals(6, plan.maxZoom)
        val expected = (0..6).flatMap { z ->
            (Pmtiles.lonToTileX(70.0, z)..Pmtiles.lonToTileX(90.0, z)).flatMap { x ->
                (Pmtiles.latToTileY(30.0, z)..Pmtiles.latToTileY(8.0, z)).map { y -> Pmtiles.zxyToTileId(z, x, y) }
            }
        }.toSet()
        assertEquals(expected.size, plan.tileCount)

        val dir = Files.createTempDir("srcs-maps-")
        val out = "$dir/india.pmtiles"
        var lastProgress = -1L
        ex.download(plan, out, progress = { done, total -> lastProgress = done; assertTrue(done <= total) })
        assertEquals(plan.bytes, lastProgress)
        val result = Files.read(out)!!
        val h = Pmtiles.readHeader(result)
        assertTrue(h.rootOffset + h.rootLength <= Pmtiles.ROOT_LIMIT)
        assertEquals(700_000_000, h.minLonE7); assertEquals(6, h.maxZoom)
        for (id in expected) assertContentEquals(tiles.getValue(id), readTile(result, id), "tile $id")
        assertNull(readTile(result, Pmtiles.zxyToTileId(6, 0, 0)), "tiles outside the area are not copied")
        assertTrue(result.size < source.size / 4, "the area is much smaller than the world")
        assertEquals(-1L, Files.size("$out.part"), "no temporary file left behind")
    }

    @Test
    fun cancelledDownloadLeavesNoFile() {
        val (source, _) = sourceArchive(maxZoom = 4)
        val ex = RegionExtractor(ByteArrayRangeReader(source))
        val plan = ex.plan(BoundingBox(-180.0, -85.0, 180.0, 85.0), 4)
        val dir = Files.createTempDir("srcs-maps-")
        assertFailsWith<RegionExtractor.CancelledException> { ex.download(plan, "$dir/x.pmtiles", cancelled = { true }) }
        assertEquals(-1L, Files.size("$dir/x.pmtiles")); assertEquals(-1L, Files.size("$dir/x.pmtiles.part"))
    }

    @Test
    fun largeAreasUseLeafDirectoriesWithinTheRootLimit() {
        // Irregular IDs, sizes and offsets compress poorly, like a real city at zoom 15.
        val rnd = Random(7)
        var id = 0L
        var off = 0L
        val entries = (0 until 60_000).map {
            id += 1 + rnd.nextInt(5)
            val len = 200 + rnd.nextInt(40_000)
            off += if (rnd.nextInt(4) == 0) rnd.nextInt(1_000_000) else 0
            Pmtiles.Entry(id, off, len, 1 + rnd.nextInt(2)).also { off += len }
        }
        val (root, leaves) = RegionExtractor.buildDirectories(entries)
        assertTrue(root.size + Pmtiles.HEADER_SIZE <= Pmtiles.ROOT_LIMIT)
        assertTrue(leaves.isNotEmpty())
        val rootEntries = Pmtiles.decodeDirectory(Gzip.decompress(root))
        val all = rootEntries.flatMap { Pmtiles.decodeDirectory(Gzip.decompress(leaves.copyOfRange(it.offset.toInt(), (it.offset + it.length).toInt()))) }
        assertEquals(entries, all)
    }
}
