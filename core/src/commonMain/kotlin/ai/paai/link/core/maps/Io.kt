package ai.paai.link.core.maps

/** gzip, as used by PMTiles directories and vector tiles. */
expect object Gzip {
    fun compress(data: ByteArray): ByteArray
    fun decompress(data: ByteArray): ByteArray
}

/** Random-access reads of a (remote or local) PMTiles archive. Blocking; throws on failure. */
interface RangeReader {
    fun read(offset: Long, length: Int): ByteArray
}

/** HTTP range requests (needs internet, used once to download a map area). */
expect class HttpRangeReader(url: String) : RangeReader {
    override fun read(offset: Long, length: Int): ByteArray
}

/** Reads a local .pmtiles file (e.g. to send it to another phone). */
class FileRangeReader(private val path: String) : RangeReader {
    override fun read(offset: Long, length: Int): ByteArray = ai.paai.link.core.util.Files.readRange(path, offset, length) ?: error("map file missing")
}

class ByteArrayRangeReader(private val bytes: ByteArray) : RangeReader {
    var requests = 0
        private set

    override fun read(offset: Long, length: Int): ByteArray {
        requests++
        val start = offset.toInt().coerceIn(0, bytes.size)
        return bytes.copyOfRange(start, (start + length).coerceAtMost(bytes.size))
    }
}
