package ai.paai.link.core.util

/** Minimal file access for app data (Android uses Room instead; iOS and tests use these). */
expect object Files {
    /** Whole file, or null if it does not exist. */
    fun read(path: String): ByteArray?
    /** Replaces the file atomically (write to a temporary file, then rename). */
    fun writeAtomic(path: String, data: ByteArray)
    /** Up to [length] bytes at [offset], or null if the file does not exist. */
    fun readRange(path: String, offset: Long, length: Int): ByteArray?
    /** Appends to the file, creating it if needed. */
    fun append(path: String, data: ByteArray)
    /** Moves [from] to [to], replacing [to]. */
    fun rename(from: String, to: String)
    /** Size in bytes, or -1 if the file does not exist. */
    fun size(path: String): Long
    /** File names (not paths) in a directory. */
    fun list(dir: String): List<String>
    fun mkdirs(path: String)
    fun delete(path: String)
    /** A fresh empty directory for tests. */
    fun createTempDir(prefix: String): String
}
