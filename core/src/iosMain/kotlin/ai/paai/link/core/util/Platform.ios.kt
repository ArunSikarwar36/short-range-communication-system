package ai.paai.link.core.util

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSDate
import platform.Foundation.NSQualityOfServiceUserInteractive
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.Foundation.timeIntervalSince1970
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicBoolean

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun secureRandomBytes(n: Int): ByteArray {
    val out = ByteArray(n)
    if (n == 0) return out
    val status = out.usePinned { SecRandomCopyBytes(kSecRandomDefault, n.convert(), it.addressOf(0)) }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed: $status" }
    return out
}

actual fun sleepMillis(ms: Long) {
    var left = ms
    while (left > 0) {
        val step = minOf(left, 1_000L)
        usleep((step * 1_000).convert())
        left -= step
    }
}

actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual suspend fun <T> runBlockingIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }

actual class WorkerThread actual constructor(name: String, block: () -> Unit) {
    private val done = AtomicBoolean(false)

    init {
        val t = NSThread {
            try {
                block()
            } finally {
                done.store(true)
            }
        }
        t.name = name
        t.qualityOfService = NSQualityOfServiceUserInteractive
        t.start()
    }

    actual fun join(timeoutMs: Long) {
        val end = currentTimeMillis() + timeoutMs
        while (!done.load() && currentTimeMillis() < end) usleep(2_000u)
    }
}

actual class Lock actual constructor() {
    private val l = NSRecursiveLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}
