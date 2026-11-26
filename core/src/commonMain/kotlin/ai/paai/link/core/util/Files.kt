package ai.paai.link.core.util

/** Minimal file access for app data (Android uses Room instead; iOS and tests use these). */
expect object Files {
    /** Whole file, or null if it does not exist. */
    fun read(path: String): ByteArray?
    /** Replaces the file atomically (write to a temporary file, then rename). */
    fun writeAtomic(path: String, data: ByteArray)
    fun mkdirs(path: String)
    fun delete(path: String)
    /** A fresh empty directory for tests. */
    fun createTempDir(prefix: String): String
}
