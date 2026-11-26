package ai.paai.link.core.util

import java.io.File
import java.nio.file.Files as NioFiles
import java.nio.file.StandardCopyOption

actual object Files {
    actual fun read(path: String): ByteArray? = File(path).takeIf { it.isFile }?.readBytes()

    actual fun writeAtomic(path: String, data: ByteArray) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(data)
        NioFiles.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    actual fun readRange(path: String, offset: Long, length: Int): ByteArray? {
        val f = File(path)
        if (!f.isFile) return null
        java.io.RandomAccessFile(f, "r").use { raf ->
            if (offset >= raf.length()) return ByteArray(0)
            raf.seek(offset)
            val n = minOf(length.toLong(), raf.length() - offset).toInt()
            return ByteArray(n).also { raf.readFully(it) }
        }
    }

    actual fun append(path: String, data: ByteArray) { File(path).appendBytes(data) }

    actual fun rename(from: String, to: String) {
        NioFiles.move(File(from).toPath(), File(to).toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    actual fun size(path: String): Long = File(path).let { if (it.isFile) it.length() else -1 }

    actual fun list(dir: String): List<String> = File(dir).list()?.sorted() ?: emptyList()

    actual fun mkdirs(path: String) { File(path).mkdirs() }

    actual fun delete(path: String) { File(path).delete() }

    actual fun createTempDir(prefix: String): String = NioFiles.createTempDirectory(prefix).toFile().absolutePath
}
