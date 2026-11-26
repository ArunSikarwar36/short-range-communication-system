package ai.paai.link.core.transport

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.INET_ADDRSTRLEN
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_DGRAM
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.SO_KEEPALIVE
import platform.posix.SO_NOSIGPIPE
import platform.posix.SO_RCVBUF
import platform.posix.SO_RCVTIMEO
import platform.posix.SO_REUSEADDR
import platform.posix.SO_SNDBUF
import platform.posix.bind
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.inet_ntop
import platform.posix.inet_pton
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.recvfrom
import platform.posix.send
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import platform.posix.timeval
import kotlin.concurrent.atomics.AtomicBoolean

private const val IPPROTO_TCP = 6
private const val TCP_NODELAY = 1
private const val POLL_SLICE_MS = 250

private fun swap16(v: Int): Int = ((v and 0xff) shl 8) or ((v ushr 8) and 0xff)
private fun lastError(what: String) = "$what: ${strerror(errno)?.toKString() ?: "error $errno"}"

private fun MemScope.address(host: String?, port: Int): sockaddr_in {
    val a = alloc<sockaddr_in>()
    a.sin_len = sizeOf<sockaddr_in>().convert()
    a.sin_family = AF_INET.convert()
    a.sin_port = swap16(port).convert()
    a.sin_addr.s_addr = 0u
    a.sin_zero.let { for (i in 0 until 8) it[i] = 0 }
    if (host != null && inet_pton(AF_INET, host, a.sin_addr.ptr) != 1) throw ConnectionLostException("not an IPv4 address: $host")
    return a
}

private fun hostOf(a: sockaddr_in): String = memScoped {
    val buf = allocArray<ByteVar>(INET_ADDRSTRLEN)
    inet_ntop(AF_INET, a.sin_addr.ptr, buf, INET_ADDRSTRLEN.convert())
    buf.toKString()
}

private fun setInt(fd: Int, level: Int, name: Int, value: Int) = memScoped {
    val v = alloc<IntVar>()
    v.value = value
    setsockopt(fd, level, name, v.ptr, sizeOf<IntVar>().convert())
}

private fun localPortOf(fd: Int): Int = memScoped {
    val a = alloc<sockaddr_in>()
    val len = alloc<socklen_tVar>()
    len.value = sizeOf<sockaddr_in>().convert()
    getsockname(fd, a.ptr.reinterpret(), len.ptr)
    swap16(a.sin_port.toInt())
}

/** Waits until [fd] is readable/writable. Returns >0 ready, 0 timeout, <0 error. */
private fun pollFd(fd: Int, events: Int, timeoutMs: Int): Int = memScoped {
    val p = alloc<pollfd>()
    p.fd = fd
    p.events = events.convert()
    p.revents = 0
    poll(p.ptr, 1u, timeoutMs)
}

private fun newSocket(type: Int): Int {
    val fd = socket(AF_INET, type, 0)
    if (fd < 0) throw ConnectionLostException(lastError("socket"))
    setInt(fd, SOL_SOCKET, SO_NOSIGPIPE, 1) // never kill the app with SIGPIPE on a closed peer
    setInt(fd, SOL_SOCKET, SO_REUSEADDR, 1)
    return fd
}

private fun bindTo(fd: Int, host: String?, port: Int) = memScoped {
    val a = address(host, port)
    if (bind(fd, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
        val msg = lastError("bind $port")
        platform.posix.close(fd)
        throw ConnectionLostException(msg)
    }
}

actual object Net {
    actual fun listen(port: Int, bindHost: String?): TcpListener {
        val fd = newSocket(SOCK_STREAM)
        bindTo(fd, bindHost, port)
        if (platform.posix.listen(fd, 16) != 0) {
            val msg = lastError("listen")
            platform.posix.close(fd)
            throw ConnectionLostException(msg)
        }
        return PosixListener(fd)
    }

    actual fun connect(host: String, port: Int, timeoutMs: Int): TcpConnection {
        val fd = newSocket(SOCK_STREAM)
        try {
            val flags = fcntl(fd, F_GETFL)
            fcntl(fd, F_SETFL, flags or O_NONBLOCK)
            val rc = memScoped {
                val a = address(host, port)
                platform.posix.connect(fd, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            }
            if (rc != 0) {
                if (errno != EINPROGRESS) throw ConnectionLostException(lastError("connect"))
                if (pollFd(fd, POLLOUT, timeoutMs) <= 0) throw ConnectionLostException("connect timed out")
                val err = memScoped {
                    val v = alloc<IntVar>()
                    val len = alloc<socklen_tVar>()
                    len.value = sizeOf<IntVar>().convert()
                    getsockopt(fd, SOL_SOCKET, SO_ERROR, v.ptr, len.ptr)
                    v.value
                }
                if (err != 0) throw ConnectionLostException("connect: ${strerror(err)?.toKString()}")
            }
            fcntl(fd, F_SETFL, flags)
            return PosixConnection(fd, host)
        } catch (e: Exception) {
            platform.posix.close(fd)
            throw e
        }
    }

    actual fun udp(port: Int, bindHost: String?): UdpSocket {
        val fd = newSocket(SOCK_DGRAM)
        setInt(fd, SOL_SOCKET, SO_RCVBUF, 256 * 1024)
        setInt(fd, SOL_SOCKET, SO_SNDBUF, 256 * 1024)
        bindTo(fd, bindHost, port)
        return PosixUdp(fd)
    }
}

private class PosixConnection(private val fd: Int, override val remoteHost: String) : TcpConnection {
    private val closed = AtomicBoolean(false)
    private var pending = ByteArray(4096)
    private var pendingLen = 0

    init {
        setInt(fd, IPPROTO_TCP, TCP_NODELAY, 1)
        setInt(fd, SOL_SOCKET, SO_KEEPALIVE, 1)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            if (closed.load()) throw ConnectionLostException("closed")
            val n = buffer.usePinned { recv(fd, it.addressOf(offset), length.convert(), 0) }.toInt()
            if (n > 0) return n
            if (n == 0) return -1
            when (errno) {
                EINTR -> continue
                EAGAIN, EWOULDBLOCK -> throw ConnectionLostException("timed out")
                else -> throw ConnectionLostException(lastError("recv"))
            }
        }
    }

    override fun write(data: ByteArray) {
        if (pendingLen + data.size > pending.size) pending = pending.copyOf(maxOf(pending.size * 2, pendingLen + data.size))
        data.copyInto(pending, pendingLen)
        pendingLen += data.size
    }

    override fun flush() {
        var off = 0
        while (off < pendingLen) {
            if (closed.load()) throw ConnectionLostException("closed")
            val n = pending.usePinned { send(fd, it.addressOf(off), (pendingLen - off).convert(), 0) }.toInt()
            if (n < 0) {
                if (errno == EINTR) continue
                pendingLen = 0
                throw ConnectionLostException(lastError("send"))
            }
            off += n
        }
        pendingLen = 0
    }

    override fun setReadTimeout(ms: Int) {
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = (ms / 1000).convert()
            tv.tv_usec = ((ms % 1000) * 1000).convert()
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            shutdown(fd, SHUT_RDWR) // wakes a thread blocked in recv
            platform.posix.close(fd)
        }
    }
}

private class PosixListener(private val fd: Int) : TcpListener {
    private val closed = AtomicBoolean(false)
    override val localPort: Int = localPortOf(fd)

    override fun accept(): TcpConnection? {
        while (!closed.load()) {
            val ready = pollFd(fd, POLLIN, POLL_SLICE_MS)
            if (closed.load()) return null
            if (ready <= 0) continue
            val conn = memScoped {
                val a = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_in>().convert()
                val c = platform.posix.accept(fd, a.ptr.reinterpret(), len.ptr)
                if (c < 0) null else c to hostOf(a)
            } ?: continue
            setInt(conn.first, SOL_SOCKET, SO_NOSIGPIPE, 1)
            return PosixConnection(conn.first, conn.second)
        }
        return null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) platform.posix.close(fd)
    }
}

private class PosixUdp(private val fd: Int) : UdpSocket {
    private val closed = AtomicBoolean(false)
    override val localPort: Int = localPortOf(fd)

    override fun send(data: ByteArray, to: NetAddress): Boolean {
        if (closed.load() || data.isEmpty()) return false
        return try {
            memScoped {
                val a = address(to.host, to.port)
                data.usePinned { sendto(fd, it.addressOf(0), data.size.convert(), 0, a.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) }.toInt() == data.size
            }
        } catch (e: ConnectionLostException) {
            false
        }
    }

    override fun receive(buffer: ByteArray): Datagram? {
        while (!closed.load()) {
            val ready = pollFd(fd, POLLIN, POLL_SLICE_MS)
            if (closed.load()) return null
            if (ready <= 0) continue
            val got = memScoped {
                val a = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>()
                len.value = sizeOf<sockaddr_in>().convert()
                val n = buffer.usePinned { recvfrom(fd, it.addressOf(0), buffer.size.convert(), 0, a.ptr.reinterpret(), len.ptr) }.toInt()
                if (n < 0) null else Datagram(n, NetAddress(hostOf(a), swap16(a.sin_port.toInt())))
            }
            if (got != null) return got
        }
        return null
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) platform.posix.close(fd)
    }
}
