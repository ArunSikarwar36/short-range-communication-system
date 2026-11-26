package ai.paai.link.core.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

actual object Files {
    actual fun read(path: String): ByteArray? {
        val d = NSData.dataWithContentsOfFile(path) ?: return null
        val n = d.length.toInt()
        val out = ByteArray(n)
        if (n > 0) out.usePinned { memcpy(it.addressOf(0), d.bytes, d.length) }
        return out
    }

    actual fun writeAtomic(path: String, data: ByteArray) {
        val nsData = if (data.isEmpty()) NSData() else data.usePinned { NSData.create(bytes = it.addressOf(0), length = data.size.convert()) }
        check(nsData.writeToFile(path, atomically = true)) { "could not write $path" }
    }

    actual fun readRange(path: String, offset: Long, length: Int): ByteArray? {
        val f = platform.posix.fopen(path, "rb") ?: return null
        try {
            if (platform.posix.fseeko(f, offset, platform.posix.SEEK_SET) != 0) return ByteArray(0)
            val buf = ByteArray(length)
            val n = if (length == 0) 0uL else buf.usePinned { platform.posix.fread(it.addressOf(0), 1u, length.convert(), f) }
            return buf.copyOf(n.toInt())
        } finally {
            platform.posix.fclose(f)
        }
    }

    actual fun append(path: String, data: ByteArray) {
        if (data.isEmpty()) return
        val f = platform.posix.fopen(path, "ab") ?: error("could not open $path")
        try {
            val n = data.usePinned { platform.posix.fwrite(it.addressOf(0), 1u, data.size.convert(), f) }
            check(n.toInt() == data.size) { "could not write $path" }
        } finally {
            platform.posix.fclose(f)
        }
    }

    actual fun rename(from: String, to: String) {
        NSFileManager.defaultManager.removeItemAtPath(to, error = null)
        check(NSFileManager.defaultManager.moveItemAtPath(from, toPath = to, error = null)) { "could not move $from" }
    }

    actual fun size(path: String): Long {
        val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path, error = null) ?: return -1
        return (attrs[platform.Foundation.NSFileSize] as? platform.Foundation.NSNumber)?.longLongValue ?: -1
    }

    actual fun list(dir: String): List<String> =
        (NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, error = null) ?: emptyList<Any>()).mapNotNull { it as? String }.sorted()

    actual fun mkdirs(path: String) {
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    actual fun delete(path: String) {
        NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    actual fun createTempDir(prefix: String): String {
        val dir = NSTemporaryDirectory().trimEnd('/') + "/" + prefix + secureRandomBytes(8).toHex()
        mkdirs(dir)
        return dir
    }
}
