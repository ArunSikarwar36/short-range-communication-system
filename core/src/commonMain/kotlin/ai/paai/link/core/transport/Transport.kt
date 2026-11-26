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
import ai.paai.link.core.util.IoDispatcher
import ai.paai.link.core.util.currentTimeMillis
import ai.paai.link.core.util.runBlockingIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.fetchAndIncrement

private val PROLOGUE = "SRCS/1".encodeToByteArray()

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
    private val connection: TcpConnection,
    val initiator: Boolean,
    val remoteStatic: ByteArray,
    val remoteHello: LinkHello,
    handshakeHash: ByteArray,
    private val sendCipher: CipherState,
    private val receiveCipher: CipherState,
) : AutoCloseable {
    val remoteId: DeviceId = DeviceId.fromPublicKey(remoteStatic)
    val sas: String = Sas.code(handshakeHash)
    val remoteHost: String = connection.remoteHost
    val connectedAt: Long = currentTimeMillis()
    val lastReceiveAt = AtomicLong(connectedAt)
    val bytesSent = AtomicLong(0L)
    val bytesReceived = AtomicLong(0L)
    private val closed = AtomicBoolean(false)
    private val outbox = Channel<Envelope>(OUTBOX_CAPACITY)
    private var jobs: List<Job> = emptyList()
    @Volatile var closeReason: String? = null
        private set

    val isOpen: Boolean get() = !closed.load()

    internal fun start(scope: CoroutineScope, listener: LinkListener) {
        val reader = scope.launch(IoDispatcher) {
            try {
                while (isActive && isOpen) {
                    val frame = FrameIO.read(connection) ?: break
                    bytesReceived.addAndFetch(frame.size + 2L)
                    val plain = receiveCipher.decryptWithAd(ByteArray(0), frame)
                    lastReceiveAt.store(currentTimeMillis())
                    listener.onEnvelope(this@Link, Envelope.decode(plain))
                }
                close("remote closed")
            } catch (e: Exception) {
                close(describe(e))
            } finally {
                listener.onClosed(this@Link, closeReason ?: "closed")
            }
        }
        val writer = scope.launch(IoDispatcher) {
            try {
                for (env in outbox) {
                    val frame = sendCipher.encryptWithAd(ByteArray(0), env.encode())
                    FrameIO.write(connection, frame)
                    bytesSent.addAndFetch(frame.size + 2L)
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
            runCatching { connection.close() }
        }
    }

    override fun close() = close("closed locally")

    override fun toString() = "Link#$id(${remoteHello.displayName}/${remoteId.short})"

    companion object {
        const val OUTBOX_CAPACITY = 512
        private val nextId = AtomicLong(1)

        private fun describe(e: Exception): String = when (e) {
            is ConnectionLostException -> "connection lost"
            else -> (e::class.simpleName ?: "error") + (e.message?.let { ": $it" } ?: "")
        }

        /**
         * Run the Noise XX handshake on a connected TCP stream. The initiator is the TCP client.
         * Messages: -> e ; <- e, ee, s, es (+responder hello) ; -> s, se (+initiator hello)
         */
        suspend fun establish(
            connection: TcpConnection,
            initiator: Boolean,
            identity: LocalIdentity,
            hello: LinkHello,
            timeoutMs: Long = 10_000,
        ): Link = withTimeout(timeoutMs) {
            runBlockingIo {
                connection.setReadTimeout(timeoutMs.toInt())
                val output = connection
                val hs = HandshakeState(NoisePattern.XX, initiator, PROLOGUE, identity.keyPair)
                fun read(): ByteArray = FrameIO.read(connection) ?: throw ConnectionLostException("closed during handshake")
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
                connection.setReadTimeout(0)
                Link(nextId.fetchAndIncrement(), connection, initiator, r.remoteStatic, remoteHello, r.handshakeHash, r.send, r.receive)
            }
        }
    }
}

/** Accepts incoming TCP connections. */
class LinkServer(private val port: Int, private val bindHost: String? = null) : AutoCloseable {
    private var server: TcpListener? = null
    val localPort: Int get() = server?.localPort ?: -1

    fun open(): Int {
        val s = Net.listen(port, bindHost)
        server = s
        return s.localPort
    }

    /** Blocks; returns null when closed. */
    fun accept(): TcpConnection? = server?.accept()

    override fun close() {
        runCatching { server?.close() }
    }
}

/** UDP socket for voice. One socket is used to both send and receive, so the source port is stable. */
class MediaSocket(port: Int, bindHost: String? = null) : AutoCloseable {
    private val socket = Net.udp(port, bindHost)
    val localPort: Int get() = socket.localPort

    fun send(data: ByteArray, to: NetAddress): Boolean = socket.send(data, to)

    /** Blocks until a datagram arrives. Returns null once closed. */
    fun receive(buffer: ByteArray): Datagram? = socket.receive(buffer)

    override fun close() = socket.close()
}
