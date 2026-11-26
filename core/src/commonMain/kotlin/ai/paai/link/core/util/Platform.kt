package ai.paai.link.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.time.TimeSource

/** Wall-clock time in milliseconds since 1970 (timestamps that are stored or sent). */
expect fun currentTimeMillis(): Long

private val monotonicStart = TimeSource.Monotonic.markNow()

/** Monotonic clock for measuring intervals; unaffected by changes to the wall clock. */
fun nanoTime(): Long = monotonicStart.elapsedNow().inWholeNanoseconds

/** Bytes from the operating system's cryptographically secure random generator. */
expect fun secureRandomBytes(n: Int): ByteArray

/** Blocks the current thread. Only for dedicated audio/IO threads, never coroutines on shared dispatchers. */
expect fun sleepMillis(ms: Long)

/** Dispatcher for blocking socket I/O. */
expect val IoDispatcher: CoroutineDispatcher

/** Runs a blocking call on [IoDispatcher]; on the JVM the thread is interrupted if the coroutine is cancelled. */
expect suspend fun <T> runBlockingIo(block: () -> T): T

/** A dedicated OS thread (voice capture / playout loops). */
expect class WorkerThread(name: String, block: () -> Unit) {
    /** Waits up to [timeoutMs] for the thread to finish. */
    fun join(timeoutMs: Long)
}

/** Re-entrant mutual exclusion lock. */
expect class Lock() {
    fun lock()
    fun unlock()
}

inline fun <T> Lock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
