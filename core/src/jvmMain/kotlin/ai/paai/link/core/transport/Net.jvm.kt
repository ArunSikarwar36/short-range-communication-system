package ai.paai.link.core.transport

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

actual object Net {
    actual fun listen(port: Int, bindHost: String?): TcpListener {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindHost?.let { InetAddress.getByName(it) }, port), 16)
        return JvmListener(s)
    }

    actual fun connect(host: String, port: Int, timeoutMs: Int): TcpConnection {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw ConnectionLostException(e.message ?: "connect failed", e)
        }
        return JvmConnection(s)
    }

    actual fun udp(port: Int, bindHost: String?): UdpSocket = JvmUdp(port, bindHost)
}

private class JvmConnection(private val socket: Socket) : TcpConnection {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())
    override val remoteHost: String = socket.inetAddress?.hostAddress ?: "?"

    init {
        socket.tcpNoDelay = true
        socket.keepAlive = true
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = io { input.read(buffer, offset, length) }
    override fun write(data: ByteArray) = io { output.write(data) }
    override fun flush() = io { output.flush() }
    override fun setReadTimeout(ms: Int) { socket.soTimeout = ms }
    override fun close() { runCatching { socket.close() } }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: SocketTimeoutException) {
        throw ConnectionLostException("timed out", e)
    } catch (e: IOException) {
        throw ConnectionLostException("connection lost", e)
    }
}

private class JvmListener(private val server: ServerSocket) : TcpListener {
    override val localPort: Int get() = server.localPort
    override fun accept(): TcpConnection? = try {
        JvmConnection(server.accept())
    } catch (e: SocketException) {
        null
    }
    override fun close() { runCatching { server.close() } }
}

private class JvmUdp(port: Int, bindHost: String?) : UdpSocket {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindHost?.let { InetAddress.getByName(it) }, port))
        receiveBufferSize = 256 * 1024
        sendBufferSize = 256 * 1024
    }
    override val localPort: Int get() = socket.localPort

    override fun send(data: ByteArray, to: NetAddress): Boolean = try {
        socket.send(DatagramPacket(data, data.size, InetSocketAddress(to.host, to.port)))
        true
    } catch (e: Exception) {
        false
    }

    override fun receive(buffer: ByteArray): Datagram? = try {
        val p = DatagramPacket(buffer, buffer.size)
        socket.receive(p)
        Datagram(p.length, NetAddress(p.address.hostAddress ?: "?", p.port))
    } catch (e: SocketException) {
        null
    }

    override fun close() = socket.close()
}
