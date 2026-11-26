package ai.paai.link.core.maps

import ai.paai.link.core.util.GrowableBytes
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.setValue
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memcpy
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream
import platform.zlib.zlibVersion

actual fun httpGetText(url: String): String {
    val nsUrl = NSURL.URLWithString(url) ?: throw IllegalArgumentException("bad URL")
    val req = NSMutableURLRequest.requestWithURL(nsUrl)
    req.setValue("SRCS-offline-maps", forHTTPHeaderField = "User-Agent")
    req.setTimeoutInterval(30.0)
    val sem = dispatch_semaphore_create(0)
    var body: NSData? = null
    var status = 0L
    NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, _ ->
        body = data
        status = (response as? NSHTTPURLResponse)?.statusCode ?: 0L
        dispatch_semaphore_signal(sem)
    }.resume()
    dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
    val d = body ?: throw IllegalStateException("no internet connection")
    if (status != 200L) throw IllegalStateException("server answered $status")
    val out = ByteArray(d.length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
    return out.decodeToString()
}

actual object Gzip {
    private const val CHUNK = 64 * 1024

    actual fun compress(data: ByteArray): ByteArray = memScoped {
        val s = alloc<z_stream>()
        // windowBits 15 + 16 = gzip wrapper.
        check(deflateInit2_(s.ptr, 6, Z_DEFLATED, 15 + 16, 8, Z_DEFAULT_STRATEGY, zlibVersion(), sizeOf<z_stream>().convert()) == Z_OK)
        val out = GrowableBytes(data.size / 2 + 64)
        val buf = ByteArray(CHUNK)
        val input = if (data.isEmpty()) ByteArray(1) else data
        input.usePinned { inp ->
            s.next_in = inp.addressOf(0).reinterpret()
            s.avail_in = data.size.convert()
            buf.usePinned { b ->
                do {
                    s.next_out = b.addressOf(0).reinterpret()
                    s.avail_out = CHUNK.convert()
                    val rc = deflate(s.ptr, Z_FINISH)
                    check(rc == Z_OK || rc == Z_STREAM_END) { "deflate failed $rc" }
                    val produced = CHUNK - s.avail_out.toInt()
                    out.write(buf.copyOf(produced))
                } while (rc != Z_STREAM_END)
            }
        }
        deflateEnd(s.ptr)
        out.toByteArray()
    }

    actual fun decompress(data: ByteArray): ByteArray = memScoped {
        if (data.isEmpty()) return@memScoped ByteArray(0)
        val s = alloc<z_stream>()
        // windowBits 15 + 32: accept gzip or zlib headers.
        check(inflateInit2_(s.ptr, 15 + 32, zlibVersion(), sizeOf<z_stream>().convert()) == Z_OK)
        val out = GrowableBytes(data.size * 4)
        val buf = ByteArray(CHUNK)
        data.usePinned { inp ->
            s.next_in = inp.addressOf(0).reinterpret()
            s.avail_in = data.size.convert()
            buf.usePinned { b ->
                while (true) {
                    s.next_out = b.addressOf(0).reinterpret()
                    s.avail_out = CHUNK.convert()
                    val rc = inflate(s.ptr, Z_NO_FLUSH)
                    if (rc != Z_OK && rc != Z_STREAM_END) {
                        inflateEnd(s.ptr)
                        throw Pmtiles.FormatException("gzip data is damaged ($rc)")
                    }
                    out.write(buf.copyOf(CHUNK - s.avail_out.toInt()))
                    if (rc == Z_STREAM_END || (s.avail_in.toInt() == 0 && s.avail_out.toInt() != 0)) break
                }
            }
        }
        inflateEnd(s.ptr)
        out.toByteArray()
    }
}

actual class HttpRangeReader actual constructor(private val url: String) : RangeReader {
    actual override fun read(offset: Long, length: Int): ByteArray {
        var attempt = 0
        while (true) {
            val r = once(offset, length)
            if (r != null) return r
            if (++attempt >= 3) throw IllegalStateException("map download failed (no answer from the map server)")
            platform.posix.sleep(attempt.convert())
        }
    }

    private fun once(offset: Long, length: Int): ByteArray? {
        val nsUrl = NSURL.URLWithString(url) ?: throw IllegalArgumentException("bad map URL")
        val req = NSMutableURLRequest.requestWithURL(nsUrl)
        req.setValue("bytes=$offset-${offset + length - 1}", forHTTPHeaderField = "Range")
        req.setValue("SRCS-offline-maps", forHTTPHeaderField = "User-Agent")
        req.setTimeoutInterval(30.0)
        val sem = dispatch_semaphore_create(0)
        var body: NSData? = null
        var status = 0L
        NSURLSession.sharedSession.dataTaskWithRequest(req) { data, response, _ ->
            body = data
            status = (response as? NSHTTPURLResponse)?.statusCode ?: 0L
            dispatch_semaphore_signal(sem)
        }.resume()
        dispatch_semaphore_wait(sem, DISPATCH_TIME_FOREVER)
        val d = body ?: return null
        if (status != 206L) throw IllegalStateException("map server answered $status (range requests needed)")
        val n = d.length.toInt()
        val out = ByteArray(n)
        if (n > 0) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
        return out
    }
}
