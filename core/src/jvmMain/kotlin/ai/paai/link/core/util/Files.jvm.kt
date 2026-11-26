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

    actual fun mkdirs(path: String) { File(path).mkdirs() }

    actual fun delete(path: String) { File(path).delete() }

    actual fun createTempDir(prefix: String): String = NioFiles.createTempDirectory(prefix).toFile().absolutePath
}
