import AVFoundation
import Foundation
import SrcsCore
import SwiftUI
import UniformTypeIdentifiers

/// DJ Mode playback: its own output-only AVAudioEngine (no voice processing), so music keeps full
/// quality. It shares the audio session with calls when one is running.
final class MusicPlayback: NSObject, AudioPlayback {
    private let engine = AVAudioEngine()
    private let ring = PcmRing(capacity: 48_000)
    private var source: AVAudioSourceNode?
    private var running = false
    private var scratch: [Int16]
    private let maxBuffered: Int

    init(codec: CodecConfig) {
        scratch = [Int16](repeating: 0, count: Int(codec.frameSamples))
        maxBuffered = Int(codec.frameSamples) * 4
    }

    func start() {
        let session = AVAudioSession.sharedInstance()
        if !VoiceIO.shared.running {
            try? session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
        }
        try? session.setActive(true)
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 1)!
        let r = ring
        let node = AVAudioSourceNode(format: format) { _, _, frameCount, abl -> OSStatus in
            let buffers = UnsafeMutableAudioBufferListPointer(abl)
            if let data = buffers[0].mData { r.readFloat(data.assumingMemoryBound(to: Float.self), Int(frameCount)) }
            return noErr
        }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        source = node
        engine.prepare()
        running = (try? engine.start()) != nil
    }

    func write(frame: KotlinShortArray, samples: Int32) -> Int32 {
        let n = min(Int(samples), scratch.count)
        for i in 0..<n { scratch[i] = frame.get(index: Int32(i)) }
        let deadline = Date().addingTimeInterval(0.2)
        while running && ring.available > maxBuffered && Date() < deadline { usleep(2_000) }
        if !running { usleep(20_000); return -1 }
        scratch.withUnsafeBufferPointer { ring.write($0.baseAddress!, n) }
        return Int32(n)
    }

    func stop() {
        running = false
        if engine.isRunning { engine.stop() }
        ring.clear()
    }

    func close() {
        stop()
        if let s = source { engine.detach(s); source = nil }
    }
}

/// Decodes a song file (MP3, AAC/M4A, WAV, AIFF, CAF…) to 48 kHz mono 16-bit PCM for DJ Mode.
final class IOSMusicSource: NSObject, MusicSource {
    private let file: AVAudioFile
    private let converter: AVAudioConverter
    private let out = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 48_000, channels: 1, interleaved: true)!
    private var pending: [Int16] = []
    private var finished = false

    init?(url: URL) {
        guard let f = try? AVAudioFile(forReading: url),
              let c = AVAudioConverter(from: f.processingFormat, to: out) else { return nil }
        file = f
        converter = c
    }

    private func fill() {
        guard !finished else { return }
        let inBuf = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: 4096)!
        do { try file.read(into: inBuf) } catch { finished = true; return }
        if inBuf.frameLength == 0 { finished = true; return }
        let cap = AVAudioFrameCount(Double(inBuf.frameLength) * 48_000 / file.processingFormat.sampleRate + 64)
        let outBuf = AVAudioPCMBuffer(pcmFormat: out, frameCapacity: cap)!
        var fed = false
        var err: NSError?
        converter.convert(to: outBuf, error: &err) { _, status in
            if fed { status.pointee = .noDataNow; return nil }
            fed = true
            status.pointee = .haveData
            return inBuf
        }
        if let p = outBuf.int16ChannelData?[0] {
            pending.append(contentsOf: UnsafeBufferPointer(start: p, count: Int(outBuf.frameLength)))
        }
    }

    func read(pcm: KotlinShortArray) -> Int32 {
        let want = Int(pcm.size)
        while pending.count < want && !finished { fill() }
        let n = min(want, pending.count)
        for i in 0..<n { pcm.set(index: Int32(i), value: pending[i]) }
        pending.removeFirst(n)
        return Int32(n)
    }

    func close() {}
}

struct Song: Identifiable, Equatable {
    let id = UUID()
    let url: URL
    let title: String
    let artist: String
    let durationMs: Int64

    static func load(_ url: URL) -> Song {
        let asset = AVURLAsset(url: url)
        let common = asset.commonMetadata
        let title = AVMetadataItem.metadataItems(from: common, filteredByIdentifier: .commonIdentifierTitle).first?.stringValue
        let artist = AVMetadataItem.metadataItems(from: common, filteredByIdentifier: .commonIdentifierArtist).first?.stringValue
        let secs = CMTimeGetSeconds(asset.duration)
        return Song(url: url, title: title ?? url.deletingPathExtension().lastPathComponent, artist: artist ?? "",
                    durationMs: secs.isFinite ? Int64(secs * 1000) : 0)
    }
}

/// DJ Mode section of the group screen.
struct DjSection: View {
    @EnvironmentObject var model: AppModel
    let isHost: Bool
    @State private var picking = false
    @State private var request = ""

    var body: some View {
        let dj = model.snap?.dj
        Section("DJ Mode") {
            switch dj?.role ?? "NONE" {
            case "DJ":
                nowPlaying(dj!)
                Text("\(dj!.listeners) listening").font(.caption).foregroundStyle(.secondary)
                HStack {
                    if !dj!.title.isEmpty && !dj!.ended {
                        Button(dj!.playing ? "Pause" : "Play") { model.controller.djPauseResume() }.buttonStyle(.borderedProminent)
                    }
                    if model.playlistIndex + 1 < model.playlist.count {
                        Button("Next") { model.djPlay(model.playlistIndex + 1) }.buttonStyle(.bordered)
                    }
                    Button("Add songs") { picking = true }.buttonStyle(.bordered)
                }
                ForEach(Array(model.playlist.enumerated()), id: \.element.id) { i, s in
                    Button {
                        model.djPlay(i)
                    } label: {
                        HStack {
                            Text(i == model.playlistIndex ? "▶" : " ").frame(width: 16)
                            VStack(alignment: .leading) {
                                Text(s.title).lineLimit(1)
                                if !s.artist.isEmpty { Text(s.artist).font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                if !(dj!.requests.isEmpty) {
                    ForEach(dj!.requests, id: \.self) { r in Text(r).font(.callout) }
                    Button("Clear requests") { model.controller.djClearRequests() }
                }
                Button("End DJ Mode", role: .destructive) { model.controller.djStop() }
            case "LISTENER":
                Text("DJ: \(dj!.djName)").font(.caption).foregroundStyle(.secondary)
                nowPlaying(dj!)
                HStack {
                    TextField("Request a song", text: $request)
                    Button("Send") {
                        model.controller.djRequest(text: request)
                        request = ""
                    }
                    .disabled(request.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                Button("Stop listening") { model.controller.djStop() }
            default:
                if isHost {
                    Text("Play songs from this phone to everyone in the group. Only music you have on the phone is shared.")
                        .font(.caption).foregroundStyle(.secondary)
                    Button("Start DJ Mode") { model.controller.djStart() }
                } else {
                    Text("When the host starts DJ Mode, the music plays here automatically.").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { model.djAddSongs(urls) }
        }
    }

    @ViewBuilder private func nowPlaying(_ d: DjView) -> some View {
        if d.title.isEmpty {
            Text("Nothing playing yet").foregroundStyle(.secondary)
        } else {
            VStack(alignment: .leading, spacing: 4) {
                Text(d.title).font(.headline).lineLimit(1)
                if !d.artist.isEmpty { Text(d.artist).font(.caption).foregroundStyle(.secondary) }
                if d.durationMs > 0 {
                    ProgressView(value: min(1, Double(d.positionMs) / Double(d.durationMs)))
                }
                Text(d.playing ? "Playing" : "Paused").font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}
