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
