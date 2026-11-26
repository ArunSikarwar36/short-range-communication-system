package ai.paai.link.core.transport

/** IPv4 address in dotted form plus port. */
data class NetAddress(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
}

/** The connection failed, timed out or was closed. */
class ConnectionLostException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Blocking TCP stream. Writes are buffered until [flush]. */
interface TcpConnection : AutoCloseable {
    val remoteHost: String
    /** Reads up to [length] bytes. Returns -1 at end of stream; throws [ConnectionLostException] on error or timeout. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int
    fun write(data: ByteArray)
    fun flush()
    /** Read timeout in milliseconds; 0 means wait forever. */
    fun setReadTimeout(ms: Int)
}

interface TcpListener : AutoCloseable {
    val localPort: Int
    /** Blocks until a client connects. Returns null once closed. */
    fun accept(): TcpConnection?
}

class Datagram(val length: Int, val from: NetAddress)

interface UdpSocket : AutoCloseable {
    val localPort: Int
    fun send(data: ByteArray, to: NetAddress): Boolean
    /** Blocks until a datagram arrives. Returns null once closed. */
    fun receive(buffer: ByteArray): Datagram?
}

/** Platform sockets: java.net on Android/JVM, BSD sockets on iOS. All calls block. */
expect object Net {
    fun listen(port: Int, bindHost: String?): TcpListener
    /** Throws [ConnectionLostException] if the connection cannot be made within [timeoutMs]. */
    fun connect(host: String, port: Int, timeoutMs: Int): TcpConnection
    fun udp(port: Int, bindHost: String?): UdpSocket
}

/** Reads exactly [length] bytes. Returns false if the stream ended before the first byte. */
fun TcpConnection.readFully(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size, eofAllowed: Boolean = false): Boolean {
    var done = 0
    while (done < length) {
        val n = read(buffer, offset + done, length - done)
        if (n < 0) {
            if (done == 0 && eofAllowed) return false
            throw ConnectionLostException("connection lost")
        }
        done += n
    }
    return true
}
