package ai.paai.link.core.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

private val random = SecureRandom()

actual fun secureRandomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

actual fun sleepMillis(ms: Long) = Thread.sleep(ms)

actual val IoDispatcher: CoroutineDispatcher get() = Dispatchers.IO

actual suspend fun <T> runBlockingIo(block: () -> T): T = runInterruptible(Dispatchers.IO) { block() }

actual class WorkerThread actual constructor(name: String, block: () -> Unit) {
    private val t = thread(name = name, isDaemon = true) { block() }
    actual fun join(timeoutMs: Long) = t.join(timeoutMs)
}

actual class Lock actual constructor() {
    private val l = ReentrantLock()
    actual fun lock() = l.lock()
    actual fun unlock() = l.unlock()
}
