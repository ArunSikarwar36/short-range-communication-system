package ai.paai.link.core.node

import ai.paai.link.core.crypto.CryptoException
import ai.paai.link.core.crypto.LocalIdentity
import ai.paai.link.core.crypto.Sealer
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.Envelope
import ai.paai.link.core.protocol.EnvelopeType
import ai.paai.link.core.protocol.LinkHello
import ai.paai.link.core.protocol.MediaPacket
import ai.paai.link.core.protocol.ProtocolLimits
import ai.paai.link.core.protocol.RouteAdvert
import ai.paai.link.core.protocol.SealedPayload
import ai.paai.link.core.routing.DedupeCache
import ai.paai.link.core.routing.Route
import ai.paai.link.core.routing.RouteTable
import ai.paai.link.core.transport.Link
import ai.paai.link.core.transport.LinkListener
import ai.paai.link.core.transport.LinkServer
import ai.paai.link.core.transport.MediaSocket
import ai.paai.link.core.util.ByteReader
import ai.paai.link.core.util.ByteWriter
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.ProtocolException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * A SRCS network node. Transport-agnostic: it works over any IP path (Wi-Fi Direct group,
 * local hotspot, or loopback in tests). Responsibilities:
 *
 *  - accept / dial TCP links and authenticate them with Noise XX;
 *  - pairing with a six-digit verification code, then persistent trust;
 *  - end-to-end sealed application messages (Noise K) routed over one or more trusted relays;
 *  - distance-vector routing, duplicate suppression, TTL, store-and-forward;
 *  - UDP media delivery and relaying with per-source rate limits.
 *
 * All mutable state is confined to a single-threaded actor context ([actor]); the media fast path
 * reads immutable snapshots only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkNode(
    val identity: LocalIdentity,
    private val trustStore: TrustStore,
    val config: NodeConfig = NodeConfig(),
    val log: LogBuffer = LogBuffer(),
    private val clock: () -> Long = System::currentTimeMillis,
) : LinkListener {
    val selfId: DeviceId = identity.deviceId

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val actor = Dispatchers.Default.limitedParallelism(1)

    private val routeTable = RouteTable(selfId, config.routeTimeoutMs)
    private val links = HashMap<DeviceId, Link>()
    private val pending = HashMap<Long, PendingPair>()
    private val trust = ConcurrentHashMap<DeviceId, TrustedPeer>()
    private val deliveredIds = DedupeCache()
    private val forwardedIds = DedupeCache()
    private val stored = ArrayDeque<Pair<Envelope, Long>>()
    private val rtt = HashMap<Long, Long>()
    private val relayCapableNeighbors = HashSet<DeviceId>()
    private val reconnectTargets = HashMap<String, ReconnectTarget>()
    private val dialing = ConcurrentHashMap.newKeySet<String>()
    private val inbound = Channel<Pair<Link, Envelope>>(2048)
    private var lastReachable: Set<DeviceId> = emptySet()

    // Media fast-path snapshots (written by the actor, read from the UDP thread).
    private val udpEndpoints = ConcurrentHashMap<DeviceId, InetSocketAddress>()
    private val endpointOwners = ConcurrentHashMap<InetSocketAddress, DeviceId>()
    @Volatile private var nextHops: Map<DeviceId, DeviceId> = emptyMap()
    private val forwardBuckets = ConcurrentHashMap<DeviceId, TokenBucket>()

    @Volatile var mediaHandler: ((MediaPacket) -> Unit)? = null
    @Volatile var displayName: String = identity.displayName
    @Volatile private var relayByUser = true
    @Volatile private var relayByBattery = true
    val relayEnabled: Boolean get() = relayByUser && relayByBattery

    private var server: LinkServer? = null
    private var udp: MediaSocket? = null
    var tcpPort: Int = -1
        private set
    var udpPort: Int = -1
        private set

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _links = MutableStateFlow<List<LinkInfo>>(emptyList())
    val linkInfos: StateFlow<List<LinkInfo>> = _links.asStateFlow()
    private val _peers = MutableStateFlow<List<PeerStatus>>(emptyList())
    val peers: StateFlow<List<PeerStatus>> = _peers.asStateFlow()
    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()
    private val _incoming = MutableSharedFlow<Incoming>(extraBufferCapacity = 512)
    val incoming: SharedFlow<Incoming> = _incoming.asSharedFlow()
    private val _events = MutableSharedFlow<NodeEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<NodeEvent> = _events.asSharedFlow()

    private class PendingPair(val link: Link, var localConfirmed: Boolean = false, var remoteConfirmed: Boolean = false, val createdAt: Long)
    /** deadline == -1 while connected; otherwise the time until which reconnecting is attempted. */
    private class ReconnectTarget(val host: String, val port: Int, var deadline: Long, var nextAttempt: Long = 0, var remoteId: DeviceId? = null)

    // ---------------------------------------------------------------- lifecycle

    suspend fun start() = withContext(actor) {
        if (_running.value) return@withContext
        trust.clear()
        trustStore.loadAll().forEach { trust[it.id] = it }
        val s = LinkServer(config.tcpPort, config.bindAddress)
        tcpPort = runInterruptible(Dispatchers.IO) { s.open() }
        server = s
        val u = MediaSocket(config.udpPort, config.bindAddress)
        udpPort = u.localPort
        udp = u
        _running.value = true
        log.i(TAG, "online: tcp $tcpPort, udp $udpPort, id ${selfId.short}")
        scope.launch { acceptLoop(s) }
        scope.launch(Dispatchers.IO) { udpLoop(u) }
        scope.launch(actor) { for ((link, env) in inbound) safely { handleEnvelope(link, env) } }
        scope.launch(actor) { while (isActive) { delay(config.advertIntervalMs); safely { advertiseAll(); expireRoutes() } } }
        scope.launch(actor) { while (isActive) { delay(config.pingIntervalMs); safely { pingAndReap() } } }
        scope.launch(actor) { while (isActive) { delay(1_000); safely { reconnectDue() } } }
        publish()
    }

    suspend fun stop() {
        withContext(actor) {
            _running.value = false
            links.values.toList().forEach { sendLocal(it, EnvelopeType.CLOSE); it.close("going offline") }
            server?.close(); udp?.close()
        }
        delay(100)
        job.cancel()
        scope.cancel()
    }

    private suspend fun acceptLoop(s: LinkServer) {
        while (scope.isActive) {
            val sock = runInterruptible(Dispatchers.IO) { s.accept() } ?: break
            scope.launch {
                try {
                    val link = Link.establish(sock, initiator = false, identity = identity, hello = hello())
                    withContext(actor) { onLinkUp(link, null) }
                } catch (e: Exception) {
                    runCatching { sock.close() }
                    log.w(TAG, "incoming handshake from ${sock.inetAddress?.hostAddress} failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun hello() = LinkHello(ProtocolLimits.VERSION, displayName.take(40), udpPort, relayEnabled)

    /**
     * Dial a device at [host]:[port] and authenticate it. On success returns the remote device ID.
     * The address is remembered so that a dropped link is retried for [NodeConfig.reconnectWindowMs].
     */
    suspend fun connect(host: String, port: Int = config.tcpPort, timeoutMs: Int = 8_000): Result<DeviceId> {
        val key = "$host:$port"
        if (!dialing.add(key)) return Result.failure(IllegalStateException("already connecting to $key"))
        return try {
            val sock = Socket()
            try {
                runInterruptible(Dispatchers.IO) { sock.connect(InetSocketAddress(host, port), timeoutMs) }
                val link = Link.establish(sock, initiator = true, identity = identity, hello = hello())
                withContext(actor) {
                    onLinkUp(link, ReconnectTarget(host, port, 0))
                }
                Result.success(link.remoteId)
            } catch (e: Exception) {
                runCatching { sock.close() }
                log.w(TAG, "connect $key failed: ${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}")
                Result.failure(e)
            }
        } finally {
            dialing.remove(key)
        }
    }

    /** Close the link to [peer] and stop reconnecting to it. */
    suspend fun disconnect(peer: DeviceId) = withContext(actor) {
        reconnectTargets.entries.removeIf { it.value.remoteId == peer }
        links[peer]?.let { sendLocal(it, EnvelopeType.CLOSE); it.close("disconnected by user") }
    }

    /** Close all links and forget dial targets (e.g. when the Wi-Fi Direct group is removed). */
    suspend fun disconnectAll() = withContext(actor) {
        reconnectTargets.clear()
        links.values.toList().forEach { sendLocal(it, EnvelopeType.CLOSE); it.close("disconnected by user") }
    }

    // ---------------------------------------------------------------- pairing / trust

    suspend fun confirmPairing(linkId: Long, accept: Boolean) = withContext(actor) {
        val p = pending[linkId] ?: return@withContext
        if (!accept) {
            sendLocal(p.link, EnvelopeType.PAIR_REJECT)
            pending.remove(linkId)
            p.link.close("pairing declined")
            log.i(TAG, "pairing with ${p.link.remoteHello.displayName} declined")
            publish()
            return@withContext
        }
        p.localConfirmed = true
        sendLocal(p.link, EnvelopeType.PAIR_CONFIRM)
        if (p.remoteConfirmed) finishPairing(p)
        publish()
    }

    /** Remove the trust record. Calls and messages with this device stop until it is paired again. */
    suspend fun forget(peer: DeviceId) = withContext(actor) {
        trust.remove(peer)
        trustStore.remove(peer)
        links[peer]?.close("unpaired")
        log.i(TAG, "forgot ${peer.short}")
        publish()
    }

    fun isTrusted(peer: DeviceId) = trust.containsKey(peer)
    fun trustedPeer(peer: DeviceId): TrustedPeer? = trust[peer]
    fun peerName(peer: DeviceId): String =
        if (peer == selfId) displayName else trust[peer]?.name ?: _routes.value.firstOrNull { it.dest == peer }?.name ?: peer.short

    private suspend fun finishPairing(p: PendingPair) {
        val link = p.link
        pending.remove(link.id)
        val peer = TrustedPeer(link.remoteId, link.remoteStatic, link.remoteHello.displayName, clock())
        trust[peer.id] = peer
        trustStore.save(peer)
        log.i(TAG, "paired with ${peer.name} (${peer.id.short})")
        _events.tryEmit(NodeEvent.Paired(peer.id, peer.name))
        markTrusted(link)
    }

    private fun markTrusted(link: Link) {
        routeTable.addNeighbor(link.remoteId, link.remoteHello.displayName, clock())
        if (link.remoteHello.relayCapable) relayCapableNeighbors += link.remoteId else relayCapableNeighbors -= link.remoteId
        val ep = InetSocketAddress(link.remoteAddress, link.remoteHello.udpPort)
        udpEndpoints.put(link.remoteId, ep)?.let { endpointOwners.remove(it) }
        endpointOwners[ep] = link.remoteId
        refreshHops()
        advertiseAll()
        flushStored()
    }

    // ---------------------------------------------------------------- link events

    private fun onLinkUp(link: Link, dialTarget: ReconnectTarget?) {
        val remote = link.remoteId
        if (remote == selfId) {
            link.close("connected to self")
            return
        }
        val existing = links[remote]
        if (existing != null && existing.isOpen) {
            val now = clock()
            val minId = minOf(selfId, remote)
            fun initiatorOf(l: Link) = if (l.initiator) selfId else remote
            val stale = now - existing.lastReceiveAt.get() > config.pingIntervalMs * 2
            val keepNew = stale || initiatorOf(link) == minId || initiatorOf(existing) != minId
            if (!keepNew) {
                link.close("duplicate link")
                return
            }
            existing.close("replaced by newer link")
        }
        links[remote] = link
        if (dialTarget != null) reconnectTargets["${dialTarget.host}:${dialTarget.port}"] = dialTarget.also { it.deadline = -1; it.remoteId = remote }
        link.start(scope, this)
        log.i(TAG, "link up: ${link.remoteHello.displayName} (${remote.short}) at ${link.remoteAddress.hostAddress}, ${if (link.initiator) "outgoing" else "incoming"}")
        if (trust.containsKey(remote)) {
            sendLocal(link, EnvelopeType.PAIR_CONFIRM) // tell the peer we already trust it
            markTrusted(link)
            _events.tryEmit(NodeEvent.PeerReachable(remote))
        } else {
            val p = PendingPair(link, createdAt = clock())
            pending[link.id] = p
            _events.tryEmit(NodeEvent.PairingRequested(info(link)))
        }
        sendLocal(link, EnvelopeType.PING, ByteWriter().u64(clock()).bytes())
        publish()
    }

    override fun onEnvelope(link: Link, envelope: Envelope) {
        if (inbound.trySend(link to envelope).isFailure) log.w(TAG, "inbound queue full, dropping ${envelope.type}")
    }

    override fun onClosed(link: Link, reason: String) {
        scope.launch(actor) { safely { onLinkDown(link, reason) } }
    }

    private fun onLinkDown(link: Link, reason: String) {
        pending.remove(link.id)?.let { _events.tryEmit(NodeEvent.PairingFailed(link.remoteHello.displayName, reason)) }
        rtt.remove(link.id)
        if (links[link.remoteId] !== link) return
        links.remove(link.remoteId)
        log.i(TAG, "link down: ${link.remoteHello.displayName}: $reason")
        val lost = routeTable.removeNeighbor(link.remoteId)
        udpEndpoints.remove(link.remoteId)?.let { endpointOwners.remove(it) }
        relayCapableNeighbors -= link.remoteId
        refreshHops()
        if (_running.value) advertiseAll()
        reconnectTargets.values.filter { it.deadline == -1L && it.remoteId == link.remoteId }.forEach {
            it.deadline = clock() + config.reconnectWindowMs
            it.nextAttempt = clock() + 1_000
        }
        if (lost.isNotEmpty()) log.d(TAG, "routes lost: ${lost.joinToString { it.short }}")
        publish()
    }

    private suspend fun handleEnvelope(link: Link, env: Envelope) {
        if (links[link.remoteId] !== link) return
        val trusted = trust.containsKey(link.remoteId) && !pending.containsKey(link.id)
        when (env.type) {
            EnvelopeType.PING -> sendLocal(link, EnvelopeType.PONG, env.body)
            EnvelopeType.PONG -> runCatching { ByteReader(env.body).u64() }.getOrNull()?.let { rtt[link.id] = clock() - it }
            EnvelopeType.PAIR_CONFIRM -> pending[link.id]?.let { p ->
                p.remoteConfirmed = true
                if (p.localConfirmed) finishPairing(p)
                publish()
            }
            EnvelopeType.PAIR_REJECT -> {
                pending.remove(link.id)?.let { _events.tryEmit(NodeEvent.PairingFailed(link.remoteHello.displayName, "declined by other device")) }
                link.close("pairing declined by peer")
            }
            EnvelopeType.CLOSE -> link.close("closed by peer")
            EnvelopeType.ROUTE_ADVERT -> if (trusted) {
                val advert = RouteAdvert.decode(env.body)
                if (advert.relayCapable) relayCapableNeighbors += link.remoteId else relayCapableNeighbors -= link.remoteId
                if (routeTable.onAdvert(link.remoteId, advert.entries, clock())) {
                    refreshHops()
                    flushStored()
                    publish()
                }
            }
            EnvelopeType.SEALED -> if (trusted) {
                if (env.dst == selfId) deliverLocal(env) else forward(env, link)
            } else {
                log.w(TAG, "dropped message from unpaired link ${link.remoteHello.displayName}")
            }
        }
    }

    private fun deliverLocal(env: Envelope) {
        val sender = trust[env.src]
        if (sender == null) {
            log.w(TAG, "dropped sealed message from unpaired device ${env.src.short}")
            return
        }
        val plain = try {
            Sealer.open(identity.keyPair, sender.staticKey, env.boundHeader(), env.body)
        } catch (e: CryptoException) {
            log.w(TAG, "rejected message claiming to be from ${sender.name}: authentication failed")
            return
        }
        val now = clock()
        if (deliveredIds.checkAndAdd(env.messageId, now)) return // replay / duplicate
        val (sentAt, msg) = try {
            SealedPayload.decode(plain)
        } catch (e: ProtocolException) {
            log.w(TAG, "malformed message from ${sender.name}: ${e.message}")
            return
        }
        if (sentAt > now + 10 * 60_000 || now - sentAt > config.maxMessageAgeMs) {
            log.w(TAG, "dropped stale message from ${sender.name}")
            return
        }
        _incoming.tryEmit(Incoming(env.src, sentAt, msg))
    }

    private fun forward(env: Envelope, from: Link) {
        if (!relayEnabled) return
        if (forwardedIds.checkAndAdd(env.messageId, clock())) return
        if (env.ttl <= 1) return
        val next = routeTable.nextHop(env.dst)?.let { links[it] }
        if (next != null && next !== from && trust.containsKey(next.remoteId)) {
            next.send(env.withTtl(env.ttl - 1))
        } else if (env.storeAndForward) {
            store(env.withTtl(env.ttl - 1))
        }
    }

    private fun store(env: Envelope) {
        while (stored.size >= config.storeForwardCapacity) stored.removeFirst()
        stored.addLast(env to clock())
        log.d(TAG, "holding message for ${peerName(env.dst)} until reachable")
    }

    private fun flushStored() {
        if (stored.isEmpty()) return
        val now = clock()
        val it = stored.iterator()
        while (it.hasNext()) {
            val (env, at) = it.next()
            if (now - at > config.storeForwardTtlMs) { it.remove(); continue }
            val link = routeTable.nextHop(env.dst)?.let { links[it] } ?: continue
            if (link.send(env)) it.remove()
        }
    }

    // ---------------------------------------------------------------- sending

    /**
     * Seal [message] for [dst] and route it. Returns true if handed to a link. With [storeAndForward],
     * a message for an unreachable device is handed to a relay-capable paired neighbour that holds it
     * until a route appears.
     */
    suspend fun send(dst: DeviceId, message: AppMessage, storeAndForward: Boolean = false): Boolean = withContext(actor) {
        val peer = trust[dst] ?: return@withContext false
        val flags = if (storeAndForward) Envelope.FLAG_STORE_FORWARD else 0
        val id = DeviceId.random()
        val header = Envelope(EnvelopeType.SEALED, ProtocolLimits.MAX_HOPS, flags, id, selfId, dst, ByteArray(0))
        val body = Sealer.seal(identity.keyPair, peer.staticKey, header.boundHeader(), SealedPayload.encode(clock(), message))
        val env = Envelope(EnvelopeType.SEALED, ProtocolLimits.MAX_HOPS, flags, id, selfId, dst, body)
        val direct = routeTable.nextHop(dst)?.let { links[it] }
        if (direct != null) return@withContext direct.send(env)
        if (storeAndForward) {
            val relay = relayCapableNeighbors.mapNotNull { links[it] }.firstOrNull { trust.containsKey(it.remoteId) }
            if (relay != null) return@withContext relay.send(env)
        }
        false
    }

    fun isReachable(peer: DeviceId): Boolean = nextHops.containsKey(peer)

    /** Hot path for voice: no locks, no allocation beyond encoding. */
    fun sendMedia(packet: MediaPacket): Boolean {
        val next = nextHops[packet.dst] ?: return false
        val ep = udpEndpoints[next] ?: return false
        return udp?.send(packet.encode(), ep) ?: false
    }

    private fun udpLoop(socket: MediaSocket) {
        val buf = ByteArray(MediaPacket.MAX_DATAGRAM + 64)
        while (scope.isActive) {
            val dp = socket.receive(buf) ?: break
            val from = InetSocketAddress(dp.address, dp.port)
            // Only paired, directly linked devices. NAT (e.g. emulators, hotspots) may rewrite the source
            // port, so fall back to the IP address when exactly one linked device uses it.
            val owner = endpointOwners[from] ?: ownerByAddress(dp.address) ?: continue
            val pkt = try {
                MediaPacket.decode(buf, dp.length)
            } catch (e: ProtocolException) {
                continue
            }
            if (pkt.dst == selfId) {
                try {
                    mediaHandler?.invoke(pkt)
                } catch (e: Exception) {
                    log.w(TAG, "media handler error: ${e.javaClass.simpleName}")
                }
                continue
            }
            if (!relayEnabled || pkt.ttl <= 1) continue
            val bucket = forwardBuckets.getOrPut(pkt.src) { TokenBucket(config.maxForwardedPacketsPerSecond) }
            if (!bucket.tryTake()) continue
            val next = nextHops[pkt.dst] ?: continue
            if (next == owner) continue
            val ep = udpEndpoints[next] ?: continue
            socket.send(pkt.readdressed(pkt.dst, pkt.ttl - 1).encode(), ep)
        }
    }

    private fun ownerByAddress(address: java.net.InetAddress): DeviceId? {
        var found: DeviceId? = null
        for ((id, ep) in udpEndpoints) {
            if (ep.address != address) continue
            if (found != null) return null // ambiguous
            found = id
        }
        return found
    }

    // ---------------------------------------------------------------- policy

    fun setRelayPolicy(allowedByUser: Boolean, allowedByBattery: Boolean) {
        val before = relayEnabled
        relayByUser = allowedByUser
        relayByBattery = allowedByBattery
        if (before != relayEnabled) {
            log.i(TAG, if (relayEnabled) "relaying enabled" else "relaying disabled")
            scope.launch(actor) { advertiseAll() }
        }
    }

    // ---------------------------------------------------------------- periodic work

    private fun advertiseAll() {
        routeTable.tick()
        for (link in links.values) {
            if (!trust.containsKey(link.remoteId) || pending.containsKey(link.id)) continue
            val entries = routeTable.advertFor(link.remoteId, displayName.take(40), relayEnabled)
            sendLocal(link, EnvelopeType.ROUTE_ADVERT, RouteAdvert(relayEnabled, entries).encode())
        }
        routeTable.clearWithdrawn()
    }

    private fun expireRoutes() {
        val lost = routeTable.expire(clock(), links.keys)
        if (lost.isNotEmpty()) {
            refreshHops()
            publish()
        }
        flushStored()
    }

    private fun pingAndReap() {
        val now = clock()
        for (link in links.values.toList()) {
            if (now - link.lastReceiveAt.get() > config.linkTimeoutMs) {
                link.close("no response for ${config.linkTimeoutMs / 1000}s")
                continue
            }
            sendLocal(link, EnvelopeType.PING, ByteWriter().u64(now).bytes())
        }
        for (p in pending.values.toList()) {
            if (now - p.createdAt > config.pairingTimeoutMs) {
                pending.remove(p.link.id)
                p.link.close("pairing timed out")
                _events.tryEmit(NodeEvent.PairingFailed(p.link.remoteHello.displayName, "timed out"))
            }
        }
        publish()
    }

    private fun reconnectDue() {
        val now = clock()
        val it = reconnectTargets.values.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.deadline == -1L) continue
            if (now > t.deadline) {
                log.i(TAG, "gave up reconnecting to ${t.host}")
                it.remove()
                continue
            }
            if (now >= t.nextAttempt) {
                t.nextAttempt = now + 3_000
                scope.launch { connect(t.host, t.port, 3_000) }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun sendLocal(link: Link, type: EnvelopeType, body: ByteArray = ByteArray(0)) {
        link.send(Envelope(type, 1, 0, DeviceId.random(), selfId, link.remoteId, body))
    }

    private fun refreshHops() {
        val snap = routeTable.snapshot()
        nextHops = snap.associate { it.dest to it.nextHop }
        _routes.value = snap
    }

    private fun info(link: Link): LinkInfo {
        val p = pending[link.id]
        return LinkInfo(
            linkId = link.id,
            remoteId = link.remoteId,
            remoteName = trust[link.remoteId]?.name ?: link.remoteHello.displayName,
            address = link.remoteAddress.hostAddress ?: "?",
            initiator = link.initiator,
            trusted = p == null && trust.containsKey(link.remoteId),
            sas = link.sas,
            localConfirmed = p?.localConfirmed ?: true,
            remoteConfirmed = p?.remoteConfirmed ?: true,
            connectedAt = link.connectedAt,
            rttMs = rtt[link.id],
            bytesSent = link.bytesSent.get(),
            bytesReceived = link.bytesReceived.get(),
            relayCapable = link.remoteHello.relayCapable,
        )
    }

    private fun publish() {
        _links.value = links.values.map { info(it) }.sortedBy { it.remoteName }
        val routeMap = routeTable.snapshot().associateBy { it.dest }
        val ids = LinkedHashSet<DeviceId>().apply { addAll(trust.keys); addAll(routeMap.keys) }
        _peers.value = ids.map { id ->
            val r = routeMap[id]
            val directLink = links[id]
            PeerStatus(
                id = id,
                name = trust[id]?.name ?: r?.name ?: directLink?.remoteHello?.displayName ?: id.short,
                trusted = trust.containsKey(id),
                reachable = r != null,
                hops = r?.hops,
                via = r?.nextHop?.takeIf { it != id },
                direct = r?.hops == 1,
            )
        }.sortedWith(compareBy({ !it.reachable }, { it.name }))
        val reachable = routeMap.keys
        for (id in reachable - lastReachable) _events.tryEmit(NodeEvent.PeerReachable(id))
        for (id in lastReachable - reachable) _events.tryEmit(NodeEvent.PeerUnreachable(id))
        lastReachable = reachable.toSet()
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ProtocolException) {
            log.w(TAG, "protocol error: ${e.message}")
        } catch (e: Exception) {
            log.e(TAG, "internal error: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "node"
    }
}
