package ai.paai.link.core.call

import ai.paai.link.core.crypto.MediaCipher
import ai.paai.link.core.media.MediaCoordinator
import ai.paai.link.core.media.MediaSessionConfig
import ai.paai.link.core.media.MediaTopology
import ai.paai.link.core.media.VoiceSession
import ai.paai.link.core.node.LinkNode
import ai.paai.link.core.protocol.AppMessage
import ai.paai.link.core.protocol.CodecConfig
import ai.paai.link.core.protocol.RejectReason
import ai.paai.link.core.util.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

/**
 * Executes [CallStateMachine] decisions: signaling over sealed messages, timers, the voice session,
 * keepalives and link-loss detection. Events are processed one at a time on a private actor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallManager(
    private val node: LinkNode,
    private val media: MediaCoordinator,
    private val scope: CoroutineScope,
    private val codec: CodecConfig = CodecConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val timerMs: (CallTimer) -> Long = { it.ms },
    private val stallMs: Long = STALL_MS,
) {
    private val actor = Dispatchers.Default.limitedParallelism(1)
    private val events = Channel<CallEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    private val _session = MutableStateFlow<VoiceSession?>(null)
    val session: StateFlow<VoiceSession?> = _session.asStateFlow()
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()
    private val _finished = MutableSharedFlow<CallState>(extraBufferCapacity = 16)
    /** Completed calls, for the call log. */
    val finished: SharedFlow<CallState> = _finished.asSharedFlow()

    private var mediaKey: ByteArray? = null
    private var callCodec: CodecConfig = codec
    private val timers = HashMap<CallTimer, Job>()
    private var monitor: Job? = null
    private var mediaStartedAt = 0L
    @Volatile private var lastKeepaliveAt = 0L
    private val pendingInvite = java.util.concurrent.ConcurrentHashMap<DeviceId, AppMessage.CallInvite>()

    init {
        scope.launch(actor) { for (e in events) apply(e) }
        scope.launch {
            node.incoming.collect { inc ->
                val m = inc.message
                val s = _state.value
                val fromPeer = inc.from == s.peer
                when (m) {
                    is AppMessage.CallInvite -> {
                        if (media.busy && s.callId != m.callId) {
                            node.send(inc.from, AppMessage.CallBusy(m.callId))
                        } else {
                            pendingInvite[m.callId] = m
                            dispatch(CallEvent.RemoteInvite(inc.from, m.callId, clock()))
                        }
                    }
                    is AppMessage.CallRinging -> if (fromPeer) dispatch(CallEvent.RemoteRinging(m.callId))
                    is AppMessage.CallAccept -> if (fromPeer) dispatch(CallEvent.RemoteAccept(m.callId))
                    is AppMessage.CallReject -> if (fromPeer) dispatch(CallEvent.RemoteReject(m.callId, m.reason))
                    is AppMessage.CallBusy -> if (fromPeer) dispatch(CallEvent.RemoteBusy(m.callId))
                    is AppMessage.CallCancel -> if (fromPeer) dispatch(CallEvent.RemoteCancel(m.callId))
                    is AppMessage.CallEnd -> if (fromPeer) dispatch(CallEvent.RemoteEnd(m.callId))
                    is AppMessage.CallKeepalive -> if (fromPeer && s.callId == m.callId) lastKeepaliveAt = clock()
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ user actions

    /** Start a call. Returns false if one is already in progress, the peer is not paired, or audio is busy. */
    fun call(peer: DeviceId): Boolean {
        if (_state.value.phase.isLive || media.busy || !node.isTrusted(peer)) return false
        dispatch(CallEvent.PlaceCall(peer, DeviceId.random(), clock()))
        return true
    }

    fun accept() = dispatch(CallEvent.LocalAccept)
    fun reject() = dispatch(CallEvent.LocalReject)
    fun hangup() = dispatch(CallEvent.LocalHangup)

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        _session.value?.muted = muted
    }

    private fun dispatch(e: CallEvent) {
        events.trySend(e)
    }

    // ------------------------------------------------------------------ state machine driver

    private suspend fun apply(e: CallEvent) {
        val before = _state.value
        val (next, actions) = CallStateMachine.reduce(before, e)
        if (next != before) {
            _state.value = next
            if (next.phase != before.phase) node.log.i(TAG, "call ${before.phase} -> ${next.phase}${next.endReason?.let { " ($it)" } ?: ""}")
        }
        if (e is CallEvent.RemoteInvite && next.callId == e.callId && next.phase == CallPhase.INCOMING_RINGING) {
            val inv = pendingInvite.remove(e.callId)
            mediaKey = inv?.mediaKey
            callCodec = inv?.codec ?: codec
        }
        if (e is CallEvent.PlaceCall && next.phase == CallPhase.OUTGOING_INVITE) {
            mediaKey = MediaCipher.newKey()
            callCodec = codec
            _muted.value = false
        }
        for (a in actions) execute(a, next)
    }

    private suspend fun execute(a: CallAction, s: CallState) {
        val peer = s.peer
        val callId = s.callId
        when (a) {
            CallAction.SendInvite -> {
                val key = mediaKey ?: return
                if (peer == null || callId == null || !node.send(peer, AppMessage.CallInvite(callId, key, callCodec))) dispatch(CallEvent.SendFailed)
            }
            CallAction.SendRinging -> sendTo(peer) { AppMessage.CallRinging(callId!!) }
            CallAction.SendAccept -> sendTo(peer) { AppMessage.CallAccept(callId!!) }
            is CallAction.SendReject -> sendTo(peer) { AppMessage.CallReject(callId!!, a.reason) }
            is CallAction.SendBusy -> node.send(a.to, AppMessage.CallBusy(a.callId))
            CallAction.SendCancel -> sendTo(peer) { AppMessage.CallCancel(callId!!) }
            CallAction.SendEnd -> sendTo(peer) { AppMessage.CallEnd(callId!!) }
            CallAction.StartMedia -> startMedia(s)
            CallAction.StopMedia -> {
                stopMedia()
                if (_state.value.phase == CallPhase.ENDING) dispatch(CallEvent.MediaReleased)
            }
            is CallAction.StartTimer -> {
                timers.remove(a.timer)?.cancel()
                timers[a.timer] = scope.launch(actor) {
                    delay(timerMs(a.timer))
                    timers.remove(a.timer)
                    apply(CallEvent.Timeout(a.timer))
                }
            }
            CallAction.CancelTimers -> {
                timers.values.forEach { it.cancel() }
                timers.clear()
            }
            is CallAction.Finished -> {
                mediaKey = null
                _finished.tryEmit(a.state)
            }
        }
    }

    private suspend fun sendTo(peer: DeviceId?, msg: () -> AppMessage) {
        if (peer != null) node.send(peer, msg())
    }

    private fun startMedia(s: CallState) {
        val key = mediaKey
        val peer = s.peer
        val callId = s.callId
        if (key == null || peer == null || callId == null) {
            dispatch(CallEvent.MediaStartFailed)
            return
        }
        val session = media.open(
            MediaSessionConfig(
                sessionId = callId,
                key = key,
                codec = callCodec,
                localStreamId = if (s.outgoing) 1 else 2,
                topology = MediaTopology.Direct(peer),
            ),
        )
        if (session == null) {
            dispatch(CallEvent.MediaStartFailed)
            return
        }
        session.muted = _muted.value
        _session.value = session
        mediaStartedAt = clock()
        lastKeepaliveAt = 0
        monitor = scope.launch {
            var tick = 0
            while (isActive) {
                delay(500)
                if (tick++ % 4 == 0) node.send(peer, AppMessage.CallKeepalive(callId))
                val now = clock()
                val last = maxOf(session.lastPacketAt, lastKeepaliveAt)
                val phase = _state.value.phase
                when {
                    phase == CallPhase.CONNECTING && last >= mediaStartedAt && last > 0 -> dispatch(CallEvent.MediaFlowing(now))
                    phase == CallPhase.ACTIVE && now - last > stallMs -> dispatch(CallEvent.MediaStalled)
                    phase == CallPhase.RECONNECTING && now - last < 1_500 -> dispatch(CallEvent.MediaFlowing(now))
                }
            }
        }
    }

    private fun stopMedia() {
        monitor?.cancel()
        monitor = null
        _session.value?.let { media.close(it) }
        _session.value = null
    }

    companion object {
        private const val TAG = "call"
        /** No audio or keepalive for this long while active → RECONNECTING. */
        const val STALL_MS = 5_000L
    }
}
