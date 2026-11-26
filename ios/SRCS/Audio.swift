import AVFoundation
import Foundation
import os
import SrcsCore

// Voice I/O for the shared core. The core's voice threads call these blocking methods:
// capture.read() waits for 20 ms of microphone audio, playback.write() paces output.
// One AVAudioEngine with Apple's voice processing (echo cancellation, noise suppression,
// gain control) serves both directions; Opus runs through ios/opus/srcs_opus.c.

/// Ring of 16-bit samples guarded by an unfair lock (cheap enough for the render thread).
final class PcmRing {
    private var buf: [Int16]
    private var readPos = 0
    private var writePos = 0
    private var count = 0
    private let lockPtr: UnsafeMutablePointer<os_unfair_lock>

    init(capacity: Int) {
        buf = [Int16](repeating: 0, count: capacity)
        lockPtr = .allocate(capacity: 1)
        lockPtr.initialize(to: os_unfair_lock())
    }

    deinit { lockPtr.deallocate() }

    private func locked<T>(_ f: () -> T) -> T {
        os_unfair_lock_lock(lockPtr)
        defer { os_unfair_lock_unlock(lockPtr) }
        return f()
    }

    var available: Int { locked { count } }

    func clear() { locked { readPos = 0; writePos = 0; count = 0 } }

    /// Appends samples; when full, the oldest audio is dropped (bounded latency).
    func write(_ src: UnsafePointer<Int16>, _ n: Int) {
        locked {
            let cap = buf.count
            for i in 0..<n {
                buf[writePos] = src[i]
                writePos = (writePos + 1) % cap
                if count == cap { readPos = (readPos + 1) % cap } else { count += 1 }
            }
        }
    }

    /// Copies up to n samples; returns how many were copied.
    func read(_ dst: UnsafeMutablePointer<Int16>, _ n: Int) -> Int {
        locked {
            let m = min(n, count)
            let cap = buf.count
            for i in 0..<m {
                dst[i] = buf[readPos]
                readPos = (readPos + 1) % cap
            }
            count -= m
            return m
        }
    }

    /// Render-thread variant: fills n floats, zero-padding on underrun.
    func readFloat(_ dst: UnsafeMutablePointer<Float>, _ n: Int) {
        locked {
            let m = min(n, count)
            let cap = buf.count
            for i in 0..<m {
                dst[i] = Float(buf[readPos]) / 32768.0
                readPos = (readPos + 1) % cap
            }
            count -= m
            if m < n { for i in m..<n { dst[i] = 0 } }
        }
    }
}

final class VoiceIO {
    static let shared = VoiceIO()

    let mic = PcmRing(capacity: 48_000)
    let speaker = PcmRing(capacity: 48_000)
    private(set) var running = false
    private let engine = AVAudioEngine()
    private let pcm48 = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 48_000, channels: 1, interleaved: true)!
    private var converter: AVAudioConverter?
    private var source: AVAudioSourceNode?
    private var users = 0
    private let lock = NSLock()
    private let log = Logger(subsystem: "ai.paai.srcs", category: "audio")

    private init() {
        NotificationCenter.default.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            self?.restartIfNeeded()
        }
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] n in
            let raw = n.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if let raw = raw, AVAudioSession.InterruptionType(rawValue: raw) == .ended { self?.restartIfNeeded() }
        }
    }

    func acquire() {
        lock.lock()
        defer { lock.unlock() }
        users += 1
        if users == 1 { start() }
    }

    func release() {
        lock.lock()
        defer { lock.unlock() }
        users = max(0, users - 1)
        if users == 0 { stop() }
    }

    /// Route call audio to the loudspeaker (true) or the earpiece / headset (false).
    func setSpeaker(_ on: Bool) {
        try? AVAudioSession.sharedInstance().overrideOutputAudioPort(on ? .speaker : .none)
    }

    private func restartIfNeeded() {
        lock.lock()
        defer { lock.unlock() }
        guard users > 0 else { return }
        stop()
        start()
    }

    private func start() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try? session.setPreferredSampleRate(48_000)
            try? session.setPreferredIOBufferDuration(0.01)
            try session.setActive(true)

            let input = engine.inputNode
            try input.setVoiceProcessingEnabled(true)
            let inFormat = input.outputFormat(forBus: 0)
            converter = AVAudioConverter(from: inFormat, to: pcm48)
            input.removeTap(onBus: 0)
            input.installTap(onBus: 0, bufferSize: 1024, format: inFormat) { [weak self] buffer, _ in
                self?.onMicrophone(buffer)
            }

            let outFormat = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1)!
            let ring = speaker
            let node = AVAudioSourceNode(format: outFormat) { _, _, frameCount, abl -> OSStatus in
                let buffers = UnsafeMutableAudioBufferListPointer(abl)
                if let data = buffers[0].mData {
                    ring.readFloat(data.assumingMemoryBound(to: Float.self), Int(frameCount))
                }
                return noErr
            }
            engine.attach(node)
            engine.connect(node, to: engine.mainMixerNode, format: outFormat)
            source = node
            engine.prepare()
            try engine.start()
            running = true
            log.info("voice engine started, input \(inFormat.sampleRate) Hz x \(inFormat.channelCount)")
        } catch {
            log.error("voice engine failed: \(error.localizedDescription)")
            stop()
        }
    }

    private func stop() {
        engine.inputNode.removeTap(onBus: 0)
        if engine.isRunning { engine.stop() }
        if let s = source {
            engine.detach(s)
            source = nil
        }
        converter = nil
        running = false
        mic.clear()
        speaker.clear()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func onMicrophone(_ buffer: AVAudioPCMBuffer) {
        guard let conv = converter else { return }
        let ratio = 48_000 / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio + 64)
        guard let out = AVAudioPCMBuffer(pcmFormat: pcm48, frameCapacity: capacity) else { return }
        var fed = false
        var error: NSError?
        conv.convert(to: out, error: &error) { _, status in
            if fed {
                status.pointee = .noDataNow
                return nil
            }
            fed = true
            status.pointee = .haveData
            return buffer
        }
        if let p = out.int16ChannelData?[0], out.frameLength > 0 {
            mic.write(p, Int(out.frameLength))
        }
    }
}

final class MicCapture: NSObject, AudioCapture {
    private var running = false
    private var scratch: [Int16]

    init(codec: CodecConfig) {
        scratch = [Int16](repeating: 0, count: Int(codec.frameSamples))
    }

    func start() {
        VoiceIO.shared.acquire()
        VoiceIO.shared.mic.clear()
        running = true
    }

    func read(frame: KotlinShortArray) -> Int32 {
        guard running else { return -1 }
        let n = min(Int(frame.size), scratch.count)
        let deadline = Date().addingTimeInterval(0.2)
        while VoiceIO.shared.mic.available < n {
            if !running || Date() > deadline { return 0 }
            usleep(2_000)
        }
        let got = scratch.withUnsafeMutableBufferPointer { VoiceIO.shared.mic.read($0.baseAddress!, n) }
        for i in 0..<got { frame.set(index: Int32(i), value: scratch[i]) }
        return Int32(got)
    }

    func stop() {
        if running {
            running = false
            VoiceIO.shared.release()
        }
    }

    func close() { stop() }
}

final class SpeakerPlayback: NSObject, AudioPlayback {
    private var running = false
    private var scratch: [Int16]
    private let maxBuffered: Int

    init(codec: CodecConfig) {
        scratch = [Int16](repeating: 0, count: Int(codec.frameSamples))
        maxBuffered = Int(codec.frameSamples) * 3
    }

    func start() {
        VoiceIO.shared.acquire()
        VoiceIO.shared.speaker.clear()
        running = true
    }

    func write(frame: KotlinShortArray, samples: Int32) -> Int32 {
        guard running else { return -1 }
        let n = min(Int(samples), scratch.count)
        for i in 0..<n { scratch[i] = frame.get(index: Int32(i)) }
        // Pace the core's playout loop to real time, like a blocking AudioTrack.
        let deadline = Date().addingTimeInterval(0.2)
        while VoiceIO.shared.running && VoiceIO.shared.speaker.available > maxBuffered && Date() < deadline {
            usleep(2_000)
        }
        if !VoiceIO.shared.running { usleep(20_000) }
        scratch.withUnsafeBufferPointer { VoiceIO.shared.speaker.write($0.baseAddress!, n) }
        return Int32(n)
    }

    func stop() {
        if running {
            running = false
            VoiceIO.shared.release()
        }
    }

    func close() { stop() }
}

final class OpusEnc: NSObject, AudioEncoder {
    private var handle: UnsafeMutableRawPointer?
    private let lock = NSLock()
    private var input: [Int16]
    private var output = [UInt8](repeating: 0, count: 1500)

    init(codec: CodecConfig, music: Bool = false) {
        handle = music ? srcs_enc_create_music(codec.sampleRate, 1, codec.bitrate) : srcs_enc_create(codec.sampleRate, 1, codec.bitrate)
        input = [Int16](repeating: 0, count: Int(codec.frameSamples))
    }

    func encode(pcm: KotlinShortArray, out: KotlinByteArray) -> Int32 {
        lock.lock()
        defer { lock.unlock() }
        guard let h = handle else { return -1 }
        let n = input.count
        for i in 0..<n { input[i] = pcm.get(index: Int32(i)) }
        let maxBytes = min(Int(out.size), output.count)
        let len: Int32 = input.withUnsafeBufferPointer { inp in
            output.withUnsafeMutableBufferPointer { o in
                srcs_enc_encode(h, inp.baseAddress, Int32(n), o.baseAddress, Int32(maxBytes))
            }
        }
        if len > 0 { for i in 0..<Int(len) { out.set(index: Int32(i), value: Int8(bitPattern: output[i])) } }
        return len
    }

    func setBitrate(bitsPerSecond: Int32) {
        lock.lock()
        defer { lock.unlock() }
        if let h = handle { _ = srcs_enc_set_bitrate(h, bitsPerSecond) }
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        if let h = handle { srcs_enc_destroy(h) }
        handle = nil
    }
}

final class OpusDec: NSObject, AudioDecoder {
    private var handle: UnsafeMutableRawPointer?
    private let lock = NSLock()
    private let frameSamples: Int
    private var pcm: [Int16]
    private var packetBytes = [UInt8](repeating: 0, count: 1500)

    init(codec: CodecConfig) {
        handle = srcs_dec_create(codec.sampleRate, 1)
        frameSamples = Int(codec.frameSamples)
        pcm = [Int16](repeating: 0, count: Int(codec.frameSamples))
    }

    func decode(packet: KotlinByteArray?, out: KotlinShortArray, fec: Bool) -> Int32 {
        lock.lock()
        defer { lock.unlock() }
        guard let h = handle else { return -1 }
        let frame = min(frameSamples, Int(out.size))
        var n: Int32 = 0
        if let p = packet {
            let len = min(Int(p.size), packetBytes.count)
            for i in 0..<len { packetBytes[i] = UInt8(bitPattern: p.get(index: Int32(i))) }
            n = packetBytes.withUnsafeBufferPointer { pk in
                pcm.withUnsafeMutableBufferPointer { o in
                    srcs_dec_decode(h, pk.baseAddress, Int32(len), o.baseAddress, Int32(frame), fec ? 1 : 0)
                }
            }
        } else {
            n = pcm.withUnsafeMutableBufferPointer { o in srcs_dec_decode(h, nil, 0, o.baseAddress, Int32(frame), 0) }
        }
        if n > 0 { for i in 0..<Int(n) { out.set(index: Int32(i), value: pcm[i]) } }
        return n
    }

    func close() {
        lock.lock()
        defer { lock.unlock() }
        if let h = handle { srcs_dec_destroy(h) }
        handle = nil
    }
}

final class IOSAudioEngine: NSObject, AudioEngine {
    func capture(codec: CodecConfig) -> AudioCapture { MicCapture(codec: codec) }
    func playback(codec: CodecConfig) -> AudioPlayback { SpeakerPlayback(codec: codec) }
    func encoder(codec: CodecConfig) -> AudioEncoder { OpusEnc(codec: codec) }
    func decoder(codec: CodecConfig) -> AudioDecoder { OpusDec(codec: codec) }
    func musicEncoder(codec: CodecConfig) -> AudioEncoder { OpusEnc(codec: codec, music: true) }
    func musicPlayback(codec: CodecConfig) -> AudioPlayback { MusicPlayback(codec: codec) }
    func onAudioThreadStart() {}
}

/// Called by the core before a call or group opens the microphone.
final class AudioHooks: NSObject, MediaHooks {
    func onAudioSessionStarting() -> Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }

    func onAudioSessionStopped() {}
}

enum Microphone {
    static func request(_ done: @escaping (Bool) -> Void) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async { done(granted) }
        }
    }
}
