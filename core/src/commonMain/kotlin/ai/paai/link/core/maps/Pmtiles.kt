package ai.paai.link.core.maps

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * PMTiles v3 (https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md): one file holding all
 * map tiles of an area, addressed by a Hilbert-curve tile ID. SRCS stores downloaded map areas in
 * this format and MapLibre reads them directly (pmtiles://file://…).
 */
object Pmtiles {
    const val HEADER_SIZE = 127
    /** Header + root directory must fit in the first 16 KiB, so readers need one request to start. */
    const val ROOT_LIMIT = 16_384

    const val COMPRESSION_NONE = 1
    const val COMPRESSION_GZIP = 2
    const val TILE_MVT = 1

    class Header(
        val rootOffset: Long, val rootLength: Long,
        val metadataOffset: Long, val metadataLength: Long,
        val leafOffset: Long, val leafLength: Long,
        val dataOffset: Long, val dataLength: Long,
        val addressedTiles: Long, val tileEntries: Long, val tileContents: Long,
        val clustered: Boolean,
        val internalCompression: Int, val tileCompression: Int, val tileType: Int,
        val minZoom: Int, val maxZoom: Int,
        val minLonE7: Int, val minLatE7: Int, val maxLonE7: Int, val maxLatE7: Int,
        val centerZoom: Int, val centerLonE7: Int, val centerLatE7: Int,
    )

    /** A run of [runLength] consecutive tile IDs with the same content, or (runLength 0) a leaf directory. */
    data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

    class FormatException(message: String) : Exception(message)

    // ------------------------------------------------------------------ header

    fun readHeader(b: ByteArray): Header {
        if (b.size < HEADER_SIZE) throw FormatException("file too short")
        if (b.copyOf(7).decodeToString() != "PMTiles") throw FormatException("not a PMTiles file")
        if (b[7].toInt() != 3) throw FormatException("unsupported PMTiles version ${b[7]}")
        return Header(
            u64(b, 8), u64(b, 16), u64(b, 24), u64(b, 32), u64(b, 40), u64(b, 48), u64(b, 56), u64(b, 64),
            u64(b, 72), u64(b, 80), u64(b, 88), b[96].toInt() == 1,
            b[97].toInt(), b[98].toInt(), b[99].toInt(), b[100].toInt() and 0xff, b[101].toInt() and 0xff,
            i32(b, 102), i32(b, 106), i32(b, 110), i32(b, 114), b[118].toInt() and 0xff, i32(b, 119), i32(b, 123),
        )
    }

    fun writeHeader(h: Header): ByteArray {
        val b = ByteArray(HEADER_SIZE)
        "PMTiles".encodeToByteArray().copyInto(b)
        b[7] = 3
        listOf(h.rootOffset, h.rootLength, h.metadataOffset, h.metadataLength, h.leafOffset, h.leafLength, h.dataOffset, h.dataLength,
            h.addressedTiles, h.tileEntries, h.tileContents).forEachIndexed { i, v -> putU64(b, 8 + i * 8, v) }
        b[96] = if (h.clustered) 1 else 0
        b[97] = h.internalCompression.toByte(); b[98] = h.tileCompression.toByte(); b[99] = h.tileType.toByte()
        b[100] = h.minZoom.toByte(); b[101] = h.maxZoom.toByte()
        putI32(b, 102, h.minLonE7); putI32(b, 106, h.minLatE7); putI32(b, 110, h.maxLonE7); putI32(b, 114, h.maxLatE7)
        b[118] = h.centerZoom.toByte(); putI32(b, 119, h.centerLonE7); putI32(b, 123, h.centerLatE7)
        return b
    }

    // ------------------------------------------------------------------ directories

    fun encodeDirectory(entries: List<Entry>): ByteArray {
        val out = ArrayList<Byte>(entries.size * 8 + 8)
        fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) { out += ((x and 0x7f) or 0x80).toByte(); x = x ushr 7 }
            out += x.toByte()
        }
        varint(entries.size.toLong())
        var last = 0L
        for (e in entries) { varint(e.tileId - last); last = e.tileId }
        for (e in entries) varint(e.runLength.toLong())
        for (e in entries) varint(e.length.toLong())
        entries.forEachIndexed { i, e ->
            val prev = if (i > 0) entries[i - 1] else null
            if (prev != null && e.offset == prev.offset + prev.length) varint(0) else varint(e.offset + 1)
        }
        return out.toByteArray()
    }

    fun decodeDirectory(b: ByteArray): List<Entry> {
        var pos = 0
        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= b.size) throw FormatException("truncated directory")
                val byte = b[pos++].toInt() and 0xff
                result = result or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw FormatException("bad varint")
            }
        }
        val n = varint().toInt()
        if (n < 0 || n > 10_000_000) throw FormatException("bad entry count")
        val ids = LongArray(n); val runs = IntArray(n); val lens = IntArray(n); val offs = LongArray(n)
        var last = 0L
        for (i in 0 until n) { last += varint(); ids[i] = last }
        for (i in 0 until n) runs[i] = varint().toInt()
        for (i in 0 until n) lens[i] = varint().toInt()
        for (i in 0 until n) {
            val v = varint()
            offs[i] = if (v == 0L && i > 0) offs[i - 1] + lens[i - 1] else v - 1
        }
        return List(n) { Entry(ids[it], offs[it], lens[it], runs[it]) }
    }

    /** Entry whose run contains [tileId] (or the leaf that may contain it), or null. Entries sorted by tileId. */
    fun find(entries: List<Entry>, tileId: Long): Entry? {
        var lo = 0
        var hi = entries.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = entries[mid].tileId.compareTo(tileId)
            if (c == 0) return entries[mid]
            if (c < 0) lo = mid + 1 else hi = mid - 1
        }
        if (hi < 0) return null
        val e = entries[hi]
        if (e.runLength == 0) return e // leaf directory covering this range
        return if (tileId - e.tileId < e.runLength) e else null
    }

    // ------------------------------------------------------------------ tile IDs (Hilbert curve)

    private val zoomStart = LongArray(27).also { a -> var acc = 0L; for (z in 0..26) { a[z] = acc; acc += 1L shl (2 * z) } }

    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26)
        val n = 1L shl z
        require(x in 0 until n && y in 0 until n) { "tile $z/$x/$y out of range" }
        var tx = x.toLong()
        var ty = y.toLong()
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (tx and s > 0) 1L else 0L
            val ry = if (ty and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            // Keep only the bits still to be processed, then rotate the quadrant.
            tx = tx and (s - 1)
            ty = ty and (s - 1)
            if (ry == 0L) {
                if (rx == 1L) { tx = s - 1 - tx; ty = s - 1 - ty }
                val t = tx; tx = ty; ty = t
            }
            s /= 2
        }
        return zoomStart[z] + d
    }

    // ------------------------------------------------------------------ Web Mercator tile ranges

    fun lonToTileX(lon: Double, z: Int): Int = floor((lon + 180.0) / 360.0 * (1 shl z)).toInt().coerceIn(0, (1 shl z) - 1)

    fun latToTileY(lat: Double, z: Int): Int {
        val r = lat.coerceIn(-85.0511, 85.0511) * PI / 180.0
        val y = (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * (1 shl z)
        return floor(y).toInt().coerceIn(0, (1 shl z) - 1)
    }

    // ------------------------------------------------------------------ little-endian helpers

    private fun u64(b: ByteArray, o: Int): Long { var v = 0L; for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff); return v }
    private fun i32(b: ByteArray, o: Int): Int { var v = 0; for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toInt() and 0xff); return v }
    private fun putU64(b: ByteArray, o: Int, v: Long) { for (i in 0 until 8) b[o + i] = (v ushr (8 * i)).toByte() }
    private fun putI32(b: ByteArray, o: Int, v: Int) { for (i in 0 until 4) b[o + i] = (v ushr (8 * i)).toByte() }
}
