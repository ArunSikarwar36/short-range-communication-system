package ai.paai.link.core.call

import ai.paai.link.core.protocol.RejectReason
import ai.paai.link.core.util.DeviceId

enum class CallPhase {
    IDLE, OUTGOING_INVITE, REMOTE_RINGING, INCOMING_RINGING, CONNECTING, ACTIVE, RECONNECTING, ENDING, ENDED, FAILED;

    val isLive: Boolean get() = this != IDLE && this != ENDED && this != FAILED
    val hasMedia: Boolean get() = this == CONNECTING || this == ACTIVE || this == RECONNECTING
}

enum class EndReason(val label: String) {
    HANGUP_LOCAL("Call ended"),
    HANGUP_REMOTE("Call ended by other side"),
    DECLINED_LOCAL("Declined"),
    DECLINED_REMOTE("Call declined"),
    BUSY("Busy"),
    NO_ANSWER("No answer"),
    CANCELLED("Cancelled"),
    MISSED("Missed call"),
    UNREACHABLE("Device unreachable"),
    NO_RESPONSE("No response from device"),
    CONNECTION_LOST("Connection lost"),
    MEDIA_FAILED("Could not start audio"),
}

data class CallState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: DeviceId? = null,
    val peer: DeviceId? = null,
    val outgoing: Boolean = false,
    val createdAt: Long = 0,
    val activeSince: Long? = null,
    val endReason: EndReason? = null,
)

sealed class CallEvent {
    data class PlaceCall(val peer: DeviceId, val callId: DeviceId, val now: Long) : CallEvent()
    data class RemoteInvite(val peer: DeviceId, val callId: DeviceId, val now: Long) : CallEvent()
    data class RemoteRinging(val callId: DeviceId) : CallEvent()
    data class RemoteAccept(val callId: DeviceId) : CallEvent()
    data class RemoteReject(val callId: DeviceId, val reason: RejectReason) : CallEvent()
    data class RemoteBusy(val callId: DeviceId) : CallEvent()
    data class RemoteCancel(val callId: DeviceId) : CallEvent()
    data class RemoteEnd(val callId: DeviceId) : CallEvent()
    data object LocalAccept : CallEvent()
    data object LocalReject : CallEvent()
    data object LocalHangup : CallEvent()
    data class MediaFlowing(val now: Long) : CallEvent()
    data object MediaStalled : CallEvent()
    data object SendFailed : CallEvent()
    data object MediaStartFailed : CallEvent()
    data object MediaReleased : CallEvent()
    data class Timeout(val timer: CallTimer) : CallEvent()
    data object Reset : CallEvent()
}

enum class CallTimer(val ms: Long) {
    /** No CALL_RINGING received. */
    INVITE(10_000),
    /** Nobody answered. */
    RING(45_000),
    /** Accepted but no audio / keepalive arrived. */
    CONNECT(12_000),
    /** Link lost mid-call: how long to wait for it to recover. */
    RECONNECT(25_000),
    /** How long an ended call stays visible before returning to idle. */
    LINGER(2_500),
}

sealed class CallAction {
    data object SendInvite : CallAction()
    data object SendRinging : CallAction()
    data object SendAccept : CallAction()
    data class SendReject(val reason: RejectReason) : CallAction()
    data class SendBusy(val to: DeviceId, val callId: DeviceId) : CallAction()
    data object SendCancel : CallAction()
    data object SendEnd : CallAction()
    data object StartMedia : CallAction()
    data object StopMedia : CallAction()
    data class StartTimer(val timer: CallTimer) : CallAction()
    data object CancelTimers : CallAction()
    data class Finished(val state: CallState) : CallAction()
}

/**
 * Pure call state machine (no I/O): (state, event) -> (state, actions).
 * Follows the specification's model:
 *   IDLE -> OUTGOING_INVITE -> REMOTE_RINGING -> CONNECTING -> ACTIVE <-> RECONNECTING -> ENDING -> ENDED / FAILED
 * with INCOMING_RINGING for the callee.
 */
object CallStateMachine {
    fun reduce(s: CallState, e: CallEvent): Pair<CallState, List<CallAction>> {
        val none = s to emptyList<CallAction>()
        fun matches(id: DeviceId) = s.callId == id

        fun end(reason: EndReason, failed: Boolean = false, vararg extra: CallAction): Pair<CallState, List<CallAction>> {
            val ns = s.copy(phase = if (failed) CallPhase.FAILED else CallPhase.ENDED, endReason = reason)
            val actions = mutableListOf<CallAction>(CallAction.CancelTimers)
            actions += extra
            if (s.phase.hasMedia) actions += CallAction.StopMedia
            actions += CallAction.Finished(ns)
            actions += CallAction.StartTimer(CallTimer.LINGER)
            return ns to actions
        }

        return when (e) {
            is CallEvent.PlaceCall ->
                if (s.phase.isLive) none
                else CallState(CallPhase.OUTGOING_INVITE, e.callId, e.peer, outgoing = true, createdAt = e.now) to
                    listOf(CallAction.CancelTimers, CallAction.SendInvite, CallAction.StartTimer(CallTimer.INVITE))

            is CallEvent.RemoteInvite ->
                if (s.phase.isLive) {
                    if (s.callId == e.callId) none else s to listOf(CallAction.SendBusy(e.peer, e.callId))
                } else CallState(CallPhase.INCOMING_RINGING, e.callId, e.peer, outgoing = false, createdAt = e.now) to
                    listOf(CallAction.CancelTimers, CallAction.SendRinging, CallAction.StartTimer(CallTimer.RING))

            is CallEvent.RemoteRinging ->
                if (matches(e.callId) && s.phase == CallPhase.OUTGOING_INVITE)
                    s.copy(phase = CallPhase.REMOTE_RINGING) to listOf(CallAction.CancelTimers, CallAction.StartTimer(CallTimer.RING))
                else none

            is CallEvent.RemoteAccept ->
                if (matches(e.callId) && (s.phase == CallPhase.OUTGOING_INVITE || s.phase == CallPhase.REMOTE_RINGING))
                    s.copy(phase = CallPhase.CONNECTING) to
                        listOf(CallAction.CancelTimers, CallAction.StartMedia, CallAction.StartTimer(CallTimer.CONNECT))
                else none

            is CallEvent.RemoteReject ->
                if (matches(e.callId) && (s.phase == CallPhase.OUTGOING_INVITE || s.phase == CallPhase.REMOTE_RINGING))
                    end(if (e.reason == RejectReason.BUSY) EndReason.BUSY else EndReason.DECLINED_REMOTE)
                else none

            is CallEvent.RemoteBusy ->
                if (matches(e.callId) && (s.phase == CallPhase.OUTGOING_INVITE || s.phase == CallPhase.REMOTE_RINGING)) end(EndReason.BUSY)
                else none

            is CallEvent.RemoteCancel ->
                if (matches(e.callId) && s.phase == CallPhase.INCOMING_RINGING) end(EndReason.MISSED) else none

            is CallEvent.RemoteEnd ->
                if (matches(e.callId) && s.phase.isLive && s.phase != CallPhase.ENDING) end(EndReason.HANGUP_REMOTE) else none

            CallEvent.LocalAccept ->
                if (s.phase == CallPhase.INCOMING_RINGING)
                    s.copy(phase = CallPhase.CONNECTING) to
                        listOf(CallAction.CancelTimers, CallAction.SendAccept, CallAction.StartMedia, CallAction.StartTimer(CallTimer.CONNECT))
                else none

            CallEvent.LocalReject ->
                if (s.phase == CallPhase.INCOMING_RINGING) end(EndReason.DECLINED_LOCAL, false, CallAction.SendReject(RejectReason.DECLINED))
                else none

            CallEvent.LocalHangup -> when (s.phase) {
                CallPhase.OUTGOING_INVITE, CallPhase.REMOTE_RINGING -> end(EndReason.CANCELLED, false, CallAction.SendCancel)
                CallPhase.INCOMING_RINGING -> end(EndReason.DECLINED_LOCAL, false, CallAction.SendReject(RejectReason.DECLINED))
                // ENDING: CALL_END is queued and media torn down; MediaReleased then completes the call.
                CallPhase.CONNECTING, CallPhase.ACTIVE, CallPhase.RECONNECTING ->
                    s.copy(phase = CallPhase.ENDING, endReason = EndReason.HANGUP_LOCAL) to
                        listOf(CallAction.CancelTimers, CallAction.SendEnd, CallAction.StopMedia)
                else -> none
            }

            is CallEvent.MediaFlowing -> when (s.phase) {
                CallPhase.CONNECTING -> s.copy(phase = CallPhase.ACTIVE, activeSince = e.now) to listOf(CallAction.CancelTimers)
                CallPhase.RECONNECTING -> s.copy(phase = CallPhase.ACTIVE) to listOf(CallAction.CancelTimers)
                else -> none
            }

            CallEvent.MediaStalled ->
                if (s.phase == CallPhase.ACTIVE) s.copy(phase = CallPhase.RECONNECTING) to listOf(CallAction.StartTimer(CallTimer.RECONNECT))
                else none

            CallEvent.SendFailed ->
                if (s.phase == CallPhase.OUTGOING_INVITE) end(EndReason.UNREACHABLE, true) else none

            CallEvent.MediaStartFailed ->
                if (s.phase.hasMedia) end(EndReason.MEDIA_FAILED, true, CallAction.SendEnd) else none

            CallEvent.MediaReleased ->
                if (s.phase == CallPhase.ENDING) end(s.endReason ?: EndReason.HANGUP_LOCAL) else none

            is CallEvent.Timeout -> when (e.timer) {
                CallTimer.INVITE -> if (s.phase == CallPhase.OUTGOING_INVITE) end(EndReason.NO_RESPONSE, true, CallAction.SendCancel) else none
                CallTimer.RING -> when (s.phase) {
                    CallPhase.REMOTE_RINGING -> end(EndReason.NO_ANSWER, false, CallAction.SendCancel)
                    CallPhase.INCOMING_RINGING -> end(EndReason.MISSED)
                    else -> none
                }
                CallTimer.CONNECT -> if (s.phase == CallPhase.CONNECTING) end(EndReason.MEDIA_FAILED, true, CallAction.SendEnd) else none
                CallTimer.RECONNECT -> if (s.phase == CallPhase.RECONNECTING) end(EndReason.CONNECTION_LOST, true, CallAction.SendEnd) else none
                CallTimer.LINGER -> if (s.phase == CallPhase.ENDED || s.phase == CallPhase.FAILED) CallState() to emptyList() else none
            }

            CallEvent.Reset -> if (s.phase.isLive) none else CallState() to listOf(CallAction.CancelTimers)
        }
    }
}
