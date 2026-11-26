package ai.paai.link.core.transport

import ai.paai.link.core.crypto.CipherState
import ai.paai.link.core.crypto.HandshakeState
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.crypto.NoisePattern
import ai.paai.link.core.crypto.Sas
import ai.paai.link.core.protocol.Envelope
import ai.paai.link.core.protocol.FrameIO
import ai.paai.link.core.protocol.LinkHello
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val PROLOGUE = "SRCS/1".toByteArray()

interface LinkListener {
    fun onEnvelope(link: Link, envelope: Envelope)
    fun onClosed(link: Link, reason: String)
}

/**
 * One authenticated, encrypted TCP connection to a directly reachable device.
 * Frames after the Noise XX handshake are Noise transport messages (implicit counter nonces),
 * so reordering, replay or modification of the stream is detected and closes the link.
 */
class Link private constructor(
    val id: Long,
    private val socket: Socket,
    val initiator: Boolean,
    val remoteStatic: ByteArray,
    val remoteHello: LinkHello,
    handshakeHash: ByteArray,
    private val sendCipher: CipherState,
    private val receiveCipher: CipherState,
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : Closeable {
    val remoteId: DeviceId = DeviceId.fromPublicKey(remoteStatic)
    val sas: String = Sas.code(handshakeHash)
    val remoteAddress: InetAddress = socket.inetAddress
    val connectedAt: Long = System.currentTimeMillis()
    val lastReceiveAt = AtomicLong(connectedAt)
    val bytesSent = AtomicLong()
    val bytesReceived = AtomicLong()
    private val closed = AtomicBoolean(false)
    private val outbox = Channel<Envelope>(OUTBOX_CAPACITY)
    private var jobs: List<Job> = emptyList()
    @Volatile var closeReason: String? = null
        private set

    val isOpen: Boolean get() = !closed.get()

    internal fun start(scope: CoroutineScope, listener: LinkListener) {
        val reader = scope.launch(Dispatchers.IO) {
            try {
                while (isActive && isOpen) {
                    val frame = FrameIO.read(input) ?: break
                    bytesReceived.addAndGet(frame.size + 2L)
                    val plain = receiveCipher.decryptWithAd(ByteArray(0), frame)
                    lastReceiveAt.set(System.currentTimeMillis())
                    listener.onEnvelope(this@Link, Envelope.decode(plain))
                }
                close("remote closed")
            } catch (e: Exception) {
                close(describe(e))
            } finally {
                listener.onClosed(this@Link, closeReason ?: "closed")
            }
        }
        val writer = scope.launch(Dispatchers.IO) {
            try {
                for (env in outbox) {
                    val frame = sendCipher.encryptWithAd(ByteArray(0), env.encode())
                    FrameIO.write(output, frame)
                    bytesSent.addAndGet(frame.size + 2L)
                }
            } catch (e: Exception) {
                close(describe(e))
            }
        }
        jobs = listOf(reader, writer)
    }

    /** Queue an envelope. Returns false if the link is closed or its queue is full (peer not reading). */
    fun send(envelope: Envelope): Boolean {
        if (!isOpen) return false
        val ok = outbox.trySend(envelope).isSuccess
        if (!ok) close("send queue overflow")
        return ok
    }

    fun close(reason: String) {
        if (closed.compareAndSet(false, true)) {
            closeReason = reason
            outbox.close()
            runCatching { socket.close() }
        }
    }

    override fun close() = close("closed locally")

    override fun toString() = "Link#$id(${remoteHello.displayName}/${remoteId.short})"

    companion object {
        const val OUTBOX_CAPACITY = 512
        private val nextId = AtomicLong(1)

        private fun describe(e: Exception): String = when (e) {
            is SocketException -> "connection lost"
            is java.io.EOFException -> "connection lost"
            else -> e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }

        /**
         * Run the Noise XX handshake on a connected socket. The initiator is the TCP client.
         * Messages: -> e ; <- e, ee, s, es (+responder hello) ; -> s, se (+initiator hello)
         */
        suspend fun establish(
            socket: Socket,
            initiator: Boolean,
            identity: LocalIdentity,
            hello: LinkHello,
            timeoutMs: Long = 10_000,
        ): Link = withTimeout(timeoutMs) {
            runInterruptible(Dispatchers.IO) {
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = timeoutMs.toInt()
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                val hs = HandshakeState(NoisePattern.XX, initiator, PROLOGUE, identity.keyPair)
                fun read(): ByteArray = FrameIO.read(input) ?: throw java.io.EOFException("closed during handshake")
                val remoteHello: LinkHello
                if (initiator) {
                    FrameIO.write(output, hs.writeMessage(ByteArray(0)))
                    remoteHello = LinkHello.decode(hs.readMessage(read()))
                    FrameIO.write(output, hs.writeMessage(hello.encode()))
                } else {
                    hs.readMessage(read())
                    FrameIO.write(output, hs.writeMessage(hello.encode()))
                    remoteHello = LinkHello.decode(hs.readMessage(read()))
                }
                val r = hs.result()
                socket.soTimeout = 0
                Link(nextId.getAndIncrement(), socket, initiator, r.remoteStatic, remoteHello, r.handshakeHash, r.send, r.receive, input, output)
            }
        }
    }
}

/** Accepts incoming TCP connections. */
class LinkServer(private val port: Int, private val bindAddress: InetAddress? = null) : Closeable {
    private var server: ServerSocket? = null
    val localPort: Int get() = server?.localPort ?: -1

    fun open(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(bindAddress, port), 16)
        server = s
        return s.localPort
    }

    /** Blocks; returns null when closed. */
    fun accept(): Socket? = try {
        server?.accept()
    } catch (e: SocketException) {
        null
    }

    override fun close() {
        runCatching { server?.close() }
    }
}

/** UDP socket for voice. One socket is used to both send and receive, so the source port is stable. */
class MediaSocket(port: Int, bindAddress: InetAddress? = null) : Closeable {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindAddress, port))
        receiveBufferSize = 256 * 1024
        sendBufferSize = 256 * 1024
    }
    val localPort: Int get() = socket.localPort

    fun send(data: ByteArray, to: InetSocketAddress): Boolean = try {
        socket.send(DatagramPacket(data, data.size, to))
        true
    } catch (e: Exception) {
        false
    }

    /** Blocks until a datagram arrives. Returns null once closed. */
    fun receive(buffer: ByteArray): DatagramPacket? = try {
        val p = DatagramPacket(buffer, buffer.size)
        socket.receive(p)
        p
    } catch (e: SocketException) {
        null
    }

    override fun close() = socket.close()
}
