package ai.paai.link.core.maps

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

actual fun httpGetText(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    c.setRequestProperty("User-Agent", "SRCS-offline-maps")
    try {
        if (c.responseCode != 200) throw java.io.IOException("server answered ${c.responseCode}")
        return c.inputStream.use { it.readBytes().decodeToString() }
    } finally {
        c.disconnect()
    }
}

actual object Gzip {
    actual fun compress(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    actual fun decompress(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
}

actual class HttpRangeReader actual constructor(private val url: String) : RangeReader {
    actual override fun read(offset: Long, length: Int): ByteArray {
        var attempt = 0
        while (true) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("Range", "bytes=$offset-${offset + length - 1}")
                c.setRequestProperty("User-Agent", "SRCS-offline-maps")
                try {
                    if (c.responseCode != 206) throw java.io.IOException("server answered ${c.responseCode} (range requests needed)")
                    return c.inputStream.use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
            } catch (e: java.io.IOException) {
                if (++attempt >= 3) throw e
                Thread.sleep(1_000L * attempt)
            }
        }
    }
}
