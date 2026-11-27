package ai.paai.link.core.protocol

import ai.paai.link.core.util.ByteReader
import ai.paai.link.core.util.ByteWriter
import ai.paai.link.core.util.DeviceId
import ai.paai.link.core.util.ProtocolException

/** Negotiated voice codec parameters. */
data class CodecConfig(val sampleRate: Int = 48_000, val frameMs: Int = 20, val bitrate: Int = 24_000) {
    val frameSamples: Int get() = sampleRate * frameMs / 1000

    fun write(w: ByteWriter) { w.u32(sampleRate.toLong()).u8(frameMs).u32(bitrate.toLong()) }

    fun validate(): CodecConfig {
        if (sampleRate !in setOf(8_000, 12_000, 16_000, 24_000, 48_000)) throw ProtocolException("unsupported sample rate")
        if (frameMs !in setOf(10, 20, 40, 60)) throw ProtocolException("unsupported frame size")
        if (bitrate !in 6_000..128_000) throw ProtocolException("unsupported bitrate")
        return this
    }

    companion object {
        fun read(r: ByteReader) = CodecConfig(r.u32().toInt(), r.u8(), r.u32().toInt()).validate()
    }
}

enum class GroupMode(val code: Int) { CALL(1), PTT(2);
    companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: throw ProtocolException("bad group mode") }
}

data class GroupMember(val id: DeviceId, val name: String, val streamId: Int)

enum class RejectReason(val code: Int) { DECLINED(1), BUSY(2), UNSUPPORTED(3), NO_PERMISSION(4);
    companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: DECLINED }
}

enum class AckStatus(val code: Int) { DELIVERED(1), READ(2);
    companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: throw ProtocolException("bad ack status") }
}

/**
 * Application messages. They travel only inside end-to-end sealed envelopes, so every one of them
 * is authenticated as coming from the envelope's source device.
 */
sealed class AppMessage(val code: Int) {
    // --- Calls (one-to-one, full duplex) ---
    data class CallInvite(val callId: DeviceId, val mediaKey: ByteArray, val codec: CodecConfig) : AppMessage(1) {
        override fun equals(other: Any?) = other is CallInvite && other.callId == callId && other.mediaKey.contentEquals(mediaKey) && other.codec == codec
        override fun hashCode() = callId.hashCode()
        override fun toString() = "CallInvite($callId)"
    }
    data class CallRinging(val callId: DeviceId) : AppMessage(2)
    data class CallAccept(val callId: DeviceId) : AppMessage(3)
    data class CallReject(val callId: DeviceId, val reason: RejectReason) : AppMessage(4)
    data class CallCancel(val callId: DeviceId) : AppMessage(5)
    data class CallEnd(val callId: DeviceId) : AppMessage(6)
    data class CallBusy(val callId: DeviceId) : AppMessage(7)
    data class CallKeepalive(val callId: DeviceId) : AppMessage(8)

    // --- Push-to-talk floor control (group host is the arbiter) ---
    data class PttRequest(val groupId: DeviceId) : AppMessage(10)
    data class PttGranted(val groupId: DeviceId, val floorSeq: Long, val maxTalkMs: Long) : AppMessage(11)
    data class PttDenied(val groupId: DeviceId, val holder: DeviceId) : AppMessage(12)
    data class PttRelease(val groupId: DeviceId, val floorSeq: Long) : AppMessage(13)
    data class PttKeepalive(val groupId: DeviceId, val floorSeq: Long) : AppMessage(14)
    data class PttRevoked(val groupId: DeviceId, val floorSeq: Long) : AppMessage(15)
    /** Broadcast by the arbiter: who holds the floor now ([DeviceId.ZERO] = free). */
    data class PttFloorState(val groupId: DeviceId, val holder: DeviceId, val floorSeq: Long) : AppMessage(16)

    // --- Groups ---
    data class GroupInvite(val groupId: DeviceId, val name: String, val mode: GroupMode, val codec: CodecConfig) : AppMessage(20)
    data class GroupJoin(val groupId: DeviceId) : AppMessage(21)
    data class GroupDecline(val groupId: DeviceId) : AppMessage(22)
    data class GroupLeave(val groupId: DeviceId) : AppMessage(23)
    data class GroupKick(val groupId: DeviceId) : AppMessage(24)
    /**
     * Full membership snapshot. Members are ordered: host first, then succession order.
     * A new [mediaKey] is issued with every epoch so that removed members cannot keep listening.
     */
    data class GroupUpdate(
        val groupId: DeviceId,
        val epoch: Long,
        val name: String,
        val mode: GroupMode,
        val codec: CodecConfig,
        val mediaKey: ByteArray,
        val members: List<GroupMember>,
    ) : AppMessage(25) {
        val host: DeviceId get() = members.first().id
        override fun equals(other: Any?) = other is GroupUpdate && other.groupId == groupId && other.epoch == epoch &&
            other.members == members && other.mediaKey.contentEquals(mediaKey) && other.name == name && other.mode == mode
        override fun hashCode() = groupId.hashCode() * 31 + epoch.hashCode()
        override fun toString() = "GroupUpdate($groupId, epoch=$epoch, members=${members.map { it.id }})"
    }
    data class GroupEnd(val groupId: DeviceId) : AppMessage(26)

    // --- Text messaging ---
    /**
     * [origin] is the author. It differs from the envelope source only when a group host relays a
     * group message; members trust the host (whom they paired with) for that attribution.
     */
    data class Text(
        val messageId: DeviceId,
        val groupId: DeviceId,
        val origin: DeviceId,
        val originName: String,
        val sentAt: Long,
        val body: String,
    ) : AppMessage(30)
    data class TextAck(val messageId: DeviceId, val status: AckStatus) : AppMessage(31)

    // --- Media quality feedback (receiver report) ---
    data class MediaReport(val sessionId: DeviceId, val lossPermille: Int, val jitterMs: Int) : AppMessage(40)

    // --- Location sharing and SOS (added without a version bump: older apps ignore unknown codes) ---
    /**
     * Where [subject] is. Sent by the subject to each recipient, or to the group host with [groupId]
     * set, which forwards it to the other members (as with group text). Coordinates in 1e-7 degrees.
     * [until] is when the sender's sharing ends (wall clock), so receivers can expire it.
     */
    data class Location(
        val subject: DeviceId,
        val subjectName: String,
        val groupId: DeviceId,
        val latE7: Int,
        val lonE7: Int,
        val accuracyM: Int,
        val fixAt: Long,
        val until: Long,
    ) : AppMessage(50)
    data class LocationStop(val subject: DeviceId, val groupId: DeviceId) : AppMessage(51)

    // --- DJ Mode: the group host streams music to the group (older apps ignore these codes) ---
    data class DjStart(val sessionId: DeviceId, val groupId: DeviceId, val djName: String, val mediaKey: ByteArray, val codec: CodecConfig) : AppMessage(60) {
        override fun equals(other: Any?) = other is DjStart && other.sessionId == sessionId && other.groupId == groupId &&
            other.djName == djName && other.mediaKey.contentEquals(mediaKey) && other.codec == codec
        override fun hashCode() = sessionId.hashCode()
        override fun toString() = "DjStart($sessionId)"
    }
    data class DjTrack(val sessionId: DeviceId, val trackId: DeviceId, val title: String, val artist: String, val durationMs: Long) : AppMessage(61)
    data class DjPlayback(val sessionId: DeviceId, val playing: Boolean, val positionMs: Long) : AppMessage(62)
    data class DjStop(val sessionId: DeviceId) : AppMessage(63)
    /** A listener asks the DJ for a song. */
    data class DjRequest(val sessionId: DeviceId, val text: String) : AppMessage(64)
    /**
     * The sender is disconnecting on purpose (the user tapped Disconnect). The receiver drops the
     * Wi-Fi link too and does not reconnect automatically. Older apps ignore this code.
     */
    data class LinkBye(val reason: Int = BYE_USER) : AppMessage(70)
    /** Emergency alert. [hasFix] is false when no GPS position is available; coordinates are then 0. */
    data class Sos(
        val alertId: DeviceId,
        val subject: DeviceId,
        val subjectName: String,
        val groupId: DeviceId,
        val hasFix: Boolean,
        val latE7: Int,
        val lonE7: Int,
        val accuracyM: Int,
        val fixAt: Long,
        val note: String,
    ) : AppMessage(52)

    fun encode(): ByteArray {
        val w = ByteWriter().u8(code)
        when (this) {
            is CallInvite -> { w.id(callId).fixed(mediaKey, 32); codec.write(w) }
            is CallRinging -> w.id(callId)
            is CallAccept -> w.id(callId)
            is CallReject -> w.id(callId).u8(reason.code)
            is CallCancel -> w.id(callId)
            is CallEnd -> w.id(callId)
            is CallBusy -> w.id(callId)
            is CallKeepalive -> w.id(callId)
            is PttRequest -> w.id(groupId)
            is PttGranted -> w.id(groupId).u32(floorSeq).u32(maxTalkMs)
            is PttDenied -> w.id(groupId).id(holder)
            is PttRelease -> w.id(groupId).u32(floorSeq)
            is PttKeepalive -> w.id(groupId).u32(floorSeq)
            is PttRevoked -> w.id(groupId).u32(floorSeq)
            is PttFloorState -> w.id(groupId).id(holder).u32(floorSeq)
            is GroupInvite -> { w.id(groupId).string(name, ProtocolLimits.MAX_NAME_BYTES).u8(mode.code); codec.write(w) }
            is GroupJoin -> w.id(groupId)
            is GroupDecline -> w.id(groupId)
            is GroupLeave -> w.id(groupId)
            is GroupKick -> w.id(groupId)
            is GroupUpdate -> {
                require(members.isNotEmpty() && members.size <= ProtocolLimits.MAX_GROUP_MEMBERS)
                w.id(groupId).u32(epoch).string(name, ProtocolLimits.MAX_NAME_BYTES).u8(mode.code)
                codec.write(w)
                w.fixed(mediaKey, 32).u8(members.size)
                for (m in members) w.id(m.id).string(m.name, ProtocolLimits.MAX_NAME_BYTES).i32(m.streamId)
            }
            is GroupEnd -> w.id(groupId)
            is Text -> w.id(messageId).id(groupId).id(origin).string(originName, ProtocolLimits.MAX_NAME_BYTES)
                .u64(sentAt).string(body, ProtocolLimits.MAX_TEXT_BYTES)
            is TextAck -> w.id(messageId).u8(status.code)
            is MediaReport -> w.id(sessionId).u16(lossPermille.coerceIn(0, 1000)).u16(jitterMs.coerceIn(0, 65535))
            is Location -> w.id(subject).string(subjectName, ProtocolLimits.MAX_NAME_BYTES).id(groupId).i32(latE7).i32(lonE7)
                .u16(accuracyM.coerceIn(0, 65535)).u64(fixAt).u64(until)
            is LocationStop -> w.id(subject).id(groupId)
            is DjStart -> { w.id(sessionId).id(groupId).string(djName, ProtocolLimits.MAX_NAME_BYTES).fixed(mediaKey, 32); codec.write(w) }
            is DjTrack -> w.id(sessionId).id(trackId).string(title, MAX_TITLE_BYTES).string(artist, MAX_TITLE_BYTES).u64(durationMs)
            is DjPlayback -> w.id(sessionId).bool(playing).u64(positionMs)
            is DjStop -> w.id(sessionId)
            is DjRequest -> w.id(sessionId).string(text, MAX_TITLE_BYTES)
            is LinkBye -> w.u8(reason)
            is Sos -> w.id(alertId).id(subject).string(subjectName, ProtocolLimits.MAX_NAME_BYTES).id(groupId).bool(hasFix)
                .i32(latE7).i32(lonE7).u16(accuracyM.coerceIn(0, 65535)).u64(fixAt).string(note, MAX_SOS_NOTE_BYTES)
        }
        return w.bytes()
    }

    companion object {
        const val MAX_SOS_NOTE_BYTES = 280
        const val BYE_USER = 0
        const val MAX_TITLE_BYTES = 200

        private fun checkCoordinates(latE7: Int, lonE7: Int) {
            if (latE7 !in -900_000_000..900_000_000 || lonE7 !in -1_800_000_000..1_800_000_000) throw ProtocolException("coordinates out of range")
        }

        private fun Location.validate() = checkCoordinates(latE7, lonE7)

        fun decode(b: ByteArray): AppMessage {
            val r = ByteReader(b)
            val m = when (val code = r.u8()) {
                1 -> CallInvite(r.id(), r.fixed(32), CodecConfig.read(r))
                2 -> CallRinging(r.id())
                3 -> CallAccept(r.id())
                4 -> CallReject(r.id(), RejectReason.of(r.u8()))
                5 -> CallCancel(r.id())
                6 -> CallEnd(r.id())
                7 -> CallBusy(r.id())
                8 -> CallKeepalive(r.id())
                10 -> PttRequest(r.id())
                11 -> PttGranted(r.id(), r.u32(), r.u32())
                12 -> PttDenied(r.id(), r.id())
                13 -> PttRelease(r.id(), r.u32())
                14 -> PttKeepalive(r.id(), r.u32())
                15 -> PttRevoked(r.id(), r.u32())
                16 -> PttFloorState(r.id(), r.id(), r.u32())
                20 -> GroupInvite(r.id(), r.string(ProtocolLimits.MAX_NAME_BYTES), GroupMode.of(r.u8()), CodecConfig.read(r))
                21 -> GroupJoin(r.id())
                22 -> GroupDecline(r.id())
                23 -> GroupLeave(r.id())
                24 -> GroupKick(r.id())
                25 -> {
                    val gid = r.id(); val epoch = r.u32(); val name = r.string(ProtocolLimits.MAX_NAME_BYTES)
                    val mode = GroupMode.of(r.u8()); val codec = CodecConfig.read(r); val key = r.fixed(32)
                    val n = r.u8()
                    if (n == 0 || n > ProtocolLimits.MAX_GROUP_MEMBERS) throw ProtocolException("bad member count")
                    val members = List(n) { GroupMember(r.id(), r.string(ProtocolLimits.MAX_NAME_BYTES), r.i32()) }
                    if (members.map { it.id }.toSet().size != n || members.map { it.streamId }.toSet().size != n) {
                        throw ProtocolException("duplicate group member or stream")
                    }
                    GroupUpdate(gid, epoch, name, mode, codec, key, members)
                }
                26 -> GroupEnd(r.id())
                30 -> Text(r.id(), r.id(), r.id(), r.string(ProtocolLimits.MAX_NAME_BYTES), r.u64(), r.string(ProtocolLimits.MAX_TEXT_BYTES))
                31 -> TextAck(r.id(), AckStatus.of(r.u8()))
                40 -> MediaReport(r.id(), r.u16(), r.u16())
                50 -> Location(r.id(), r.string(ProtocolLimits.MAX_NAME_BYTES), r.id(), r.i32(), r.i32(), r.u16(), r.u64(), r.u64()).also { it.validate() }
                51 -> LocationStop(r.id(), r.id())
                60 -> DjStart(r.id(), r.id(), r.string(ProtocolLimits.MAX_NAME_BYTES), r.fixed(32), CodecConfig.read(r))
                61 -> DjTrack(r.id(), r.id(), r.string(MAX_TITLE_BYTES), r.string(MAX_TITLE_BYTES), r.u64())
                62 -> DjPlayback(r.id(), r.bool(), r.u64())
                63 -> DjStop(r.id())
                64 -> DjRequest(r.id(), r.string(MAX_TITLE_BYTES))
                70 -> LinkBye(r.u8())
                52 -> Sos(r.id(), r.id(), r.string(ProtocolLimits.MAX_NAME_BYTES), r.id(), r.bool(), r.i32(), r.i32(), r.u16(), r.u64(), r.string(MAX_SOS_NOTE_BYTES))
                    .also { if (it.hasFix) checkCoordinates(it.latE7, it.lonE7) }
                else -> throw ProtocolException("unknown app message $code")
            }
            r.requireEnd()
            return m
        }
    }
}

/**
 * Sealed payload = [sentAt u64][AppMessage]. The timestamp is a coarse freshness check only;
 * replay protection relies on message-ID de-duplication.
 */
object SealedPayload {
    fun encode(sentAt: Long, msg: AppMessage): ByteArray = ByteWriter().u64(sentAt).raw(msg.encode()).bytes()

    fun decode(b: ByteArray): Pair<Long, AppMessage> {
        val r = ByteReader(b)
        val t = r.u64()
        return t to AppMessage.decode(r.rest())
    }
}

enum class MediaKind(val code: Int) { VOICE(1), PROBE(2), MUSIC(3);
    companion object { fun of(c: Int) = entries.firstOrNull { it.code == c } ?: throw ProtocolException("bad media kind") }
}

/**
 * UDP media packet:
 *  magic 'P' | version | kind | ttl | src(16) | dst(16) | session(16) | stream u32 | seq u32 | ts u32 | ciphertext
 * The AEAD additional data is the header without ttl and dst, so a relay or group hub can
 * re-address a packet but cannot alter anything else.
 */
class MediaPacket(
    val kind: MediaKind,
    val ttl: Int,
    val src: DeviceId,
    val dst: DeviceId,
    val sessionId: DeviceId,
    val streamId: Int,
    val seq: Long,
    val timestamp: Long,
    val ciphertext: ByteArray,
) {
    fun aad(): ByteArray = ByteWriter(64).u8(MAGIC).u8(ProtocolLimits.VERSION).u8(kind.code)
        .id(src).id(sessionId).i32(streamId).u32(seq).u32(timestamp).bytes()

    fun encode(): ByteArray = ByteWriter(HEADER_SIZE + ciphertext.size).u8(MAGIC).u8(ProtocolLimits.VERSION).u8(kind.code).u8(ttl)
        .id(src).id(dst).id(sessionId).i32(streamId).u32(seq).u32(timestamp).raw(ciphertext).bytes()

    fun readdressed(newDst: DeviceId, newTtl: Int) = MediaPacket(kind, newTtl, src, newDst, sessionId, streamId, seq, timestamp, ciphertext)

    companion object {
        const val MAGIC = 0x50
        const val HEADER_SIZE = 4 + 16 * 3 + 12
        const val MAX_DATAGRAM = HEADER_SIZE + ProtocolLimits.MAX_MEDIA_PAYLOAD + 16

        fun decode(b: ByteArray, len: Int = b.size): MediaPacket {
            if (len < HEADER_SIZE + 16 || len > MAX_DATAGRAM) throw ProtocolException("bad media packet size $len")
            val r = ByteReader(b, 0, len)
            if (r.u8() != MAGIC) throw ProtocolException("bad magic")
            if (r.u8() != ProtocolLimits.VERSION) throw ProtocolException("bad media version")
            val kind = MediaKind.of(r.u8())
            val ttl = r.u8()
            if (ttl > ProtocolLimits.MAX_HOPS) throw ProtocolException("ttl too large")
            return MediaPacket(kind, ttl, r.id(), r.id(), r.id(), r.i32(), r.u32(), r.u32(), r.rest())
        }
    }
}
