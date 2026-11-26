package ai.paai.link.core.media

import ai.paai.link.core.node.LinkNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform hooks around a live audio session (Android: audio focus, routing, microphone foreground service). */
interface MediaHooks {
    /** Return false to refuse (e.g. microphone permission missing). */
    fun onAudioSessionStarting(): Boolean = true
    fun onAudioSessionStopped() {}
}

/**
 * Owns the single live voice session. Calls, group calls and push-to-talk are mutually exclusive,
 * because a phone has one microphone and one voice-communication audio route.
 */
class MediaCoordinator(
    private val node: LinkNode,
    private val engine: AudioEngine,
    private val scope: CoroutineScope,
    private val hooks: MediaHooks = object : MediaHooks {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val router = MediaRouter(node, scope)
    private val _active = MutableStateFlow<VoiceSession?>(null)
    val active: StateFlow<VoiceSession?> = _active.asStateFlow()

    val busy: Boolean get() = _active.value != null

    @Synchronized
    fun open(config: MediaSessionConfig): VoiceSession? {
        if (_active.value != null) {
            node.log.w("media", "another audio session is active")
            return null
        }
        if (!hooks.onAudioSessionStarting()) {
            node.log.w("media", "audio session refused by platform (permission or audio focus)")
            return null
        }
        return try {
            VoiceSession(node, engine, config, scope, clock).also {
                router.register(it)
                _active.value = it
                node.log.i("media", "session ${config.sessionId.short} started (${config.codec.bitrate / 1000} kbps, ${config.codec.frameMs} ms frames)")
            }
        } catch (e: Exception) {
            node.log.e("media", "could not start audio: ${e.javaClass.simpleName}: ${e.message}")
            hooks.onAudioSessionStopped()
            null
        }
    }

    @Synchronized
    fun close(session: VoiceSession) {
        router.unregister(session)
        session.close()
        if (_active.value === session) {
            _active.value = null
            hooks.onAudioSessionStopped()
        }
    }
}
