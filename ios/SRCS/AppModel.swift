import Foundation
import SrcsCore
import SwiftUI
import UIKit

/// Bridges the shared Kotlin controller to SwiftUI. The controller calls back on background
/// threads; every update is moved to the main thread here.
final class AppModel: ObservableObject {
    @Published private(set) var snap: AppSnapshot?
    @Published var notice: String?
    @Published var onboarded: Bool { didSet { defaults.set(onboarded, forKey: "onboarded") } }
    @Published var displayName: String
    @Published var speakerOn = false
    /// DJ Mode playlist (songs copied into the app so they stay readable).
    @Published private(set) var playlist: [Song] = []
    @Published private(set) var playlistIndex = -1
    @Published var autoConnect: Bool {
        didSet {
            defaults.set(autoConnect, forKey: "autoConnect")
            controller.setAutoConnect(enabled: autoConnect)
        }
    }

    @Published var autoJoinWalkie: Bool {
        didSet {
            defaults.set(autoJoinWalkie, forKey: "autoJoinWalkie")
            controller.autoJoinWalkie = join
        }
    }

    let controller: SrcsController
    let gps: GpsProvider
    private var sosPending = false
    private var mapOpen = false
    private let defaults = UserDefaults.standard
    private var watchers: [Watcher] = []
    private var noticeTimer: Timer?

    init() {
        let args = ProcessInfo.processInfo.arguments
        if args.contains("-resetForUITest") {
            Keychain.reset()
            if let domain = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: domain) }
            try? FileManager.default.removeItem(atPath: AppModel.dataDir)
        }
        let stored = UserDefaults.standard
        let wasOnboarded = stored.bool(forKey: "onboarded")
        let name = stored.string(forKey: "displayName") ?? UIDevice.current.name
        let auto = stored.object(forKey: "autoConnect") as? Bool ?? true
        onboarded = wasOnboarded
        displayName = name
        autoConnect = auto
        let join = stored.object(forKey: "autoJoinWalkie") as? Bool ?? true
        autoJoinWalkie = join
        controller = SrcsController.Companion.shared.create(
            dataDir: AppModel.dataDir,
            privateKey: Keychain.identityKey(),
            displayName: name,
            audio: IOSAudioEngine(),
            hooks: AudioHooks()
        )
        controller.setAutoConnect(enabled: auto)
        controller.autoJoinWalkie = join
        gps = GpsProvider(controller: controller)
        watchers.append(controller.watch { [weak self] s in
            DispatchQueue.main.async { self?.apply(s) }
        })
        watchers.append(controller.watchNotices { [weak self] text in
            DispatchQueue.main.async { self?.show(text) }
        })
        if wasOnboarded && stored.bool(forKey: "wasOnline") { controller.goOnline() }
    }

    static var dataDir: String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("srcs", isDirectory: true).path
    }

    private func apply(_ s: AppSnapshot) {
        let wasEnded = snap?.dj.ended ?? false
        snap = s
        // Play the next song when one ends.
        if s.dj.role == "DJ" && s.dj.ended && !wasEnded && playlistIndex + 1 < playlist.count {
            djPlay(playlistIndex + 1)
        }
        if s.dj.role == "NONE" && playlistIndex >= 0 { playlistIndex = -1 }
        let live = s.call?.live == true
        UIApplication.shared.isIdleTimerDisabled = live || s.group != nil
        UIDevice.current.isProximityMonitoringEnabled = live && !speakerOn
        updateGps()
    }

    /// GPS only while sharing, sending an SOS, or showing friends' positions.
    private func updateGps() {
        let sharing = snap?.location.sharing == true
        let friends = !(snap?.friends.isEmpty ?? true)
        gps.setWanted(sharing || sosPending || friends || mapOpen, inBackground: sharing)
    }

    // MARK: - DJ Mode

    func djAddSongs(_ urls: [URL]) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("music", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var added: [Song] = []
        for url in urls {
            let ok = url.startAccessingSecurityScopedResource()
            defer { if ok { url.stopAccessingSecurityScopedResource() } }
            let copy = dir.appendingPathComponent(UUID().uuidString + "-" + url.lastPathComponent)
            if (try? FileManager.default.copyItem(at: url, to: copy)) != nil { added.append(Song.load(copy)) }
        }
        let first = playlist.count
        playlist.append(contentsOf: added)
        if playlistIndex < 0 && !added.isEmpty && (snap?.dj.title.isEmpty ?? true) { djPlay(first) }
    }

    func djPlay(_ index: Int) {
        guard index >= 0 && index < playlist.count else { return }
        let song = playlist[index]
        guard let source = IOSMusicSource(url: song.url) else {
            show("Cannot play '\(song.title)'")
            return
        }
        playlistIndex = index
        controller.djPlay(title: song.title, artist: song.artist, durationMs: song.durationMs, source: source)
    }

    /// The map screens show this phone's position, so GPS runs while they are open.
    func setMapOpen(_ open: Bool) {
        mapOpen = open
        updateGps()
    }

    /// Waits up to 8 s for a GPS fix so the SOS can carry coordinates, then sends it.
    func sendSos(_ note: String) {
        sosPending = true
        updateGps()
        show("Getting your position…")
        let deadline = Date().addingTimeInterval(8)
        func attempt() {
            if snap?.location.hasFix == true || Date() > deadline {
                controller.sendSos(note: note)
                sosPending = false
                updateGps()
            } else {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5, execute: attempt)
            }
        }
        attempt()
    }

    func show(_ text: String) {
        notice = text
        noticeTimer?.invalidate()
        noticeTimer = Timer.scheduledTimer(withTimeInterval: 3.5, repeats: false) { [weak self] _ in self?.notice = nil }
    }

    // MARK: - Commands

    func finishOnboarding(name: String) {
        setName(name)
        onboarded = true
    }

    func setName(_ name: String) {
        let n = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !n.isEmpty else { return }
        displayName = String(n.prefix(40))
        defaults.set(displayName, forKey: "displayName")
        controller.setDisplayName(newName: displayName)
    }

    func setOnline(_ on: Bool) {
        defaults.set(on, forKey: "wasOnline")
        if on { controller.goOnline() } else { controller.goOffline() }
    }

    /// Accepts "host" or "host:port"; the port defaults to 47700.
    func connect(to address: String) {
        let parts = address.trimmingCharacters(in: .whitespaces).split(separator: ":")
        guard let host = parts.first, !host.isEmpty else { return }
        let port = parts.count > 1 ? Int32(parts[1]) ?? 47700 : 47700
        controller.connect(host: String(host), port: port)
    }

    func call(_ peerId: String) {
        Microphone.request { granted in
            if granted { self.speakerOn = false; self.controller.call(peerId: peerId) } else { self.show("SRCS needs the microphone for calls. Allow it in Settings.") }
        }
    }

    func answer() {
        Microphone.request { granted in
            if granted { self.speakerOn = false; self.controller.answer() } else { self.controller.decline(); self.show("Microphone not allowed") }
        }
    }

    func joinGroup(_ groupId: String) {
        Microphone.request { granted in
            if granted { self.controller.acceptInvite(groupId: groupId) } else { self.show("SRCS needs the microphone for group calls.") }
        }
    }

    /// Start a walkie-talkie with every paired phone in range; they join it automatically.
    func startWalkieTalkie() {
        Microphone.request { granted in
            if granted { self.setSpeaker(true); self.controller.startWalkieTalkie() } else { self.show("SRCS needs the microphone for the walkie-talkie.") }
        }
    }

    func setSpeaker(_ on: Bool) {
        speakerOn = on
        VoiceIO.shared.setSpeaker(on)
        UIDevice.current.isProximityMonitoringEnabled = (snap?.call?.live == true) && !on
    }
}
