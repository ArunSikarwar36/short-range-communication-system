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
    @Published var autoConnect: Bool {
        didSet {
            defaults.set(autoConnect, forKey: "autoConnect")
            controller.setAutoConnect(enabled: autoConnect)
        }
    }

    let controller: SrcsController
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
        controller = SrcsController.Companion.shared.create(
            dataDir: AppModel.dataDir,
            privateKey: Keychain.identityKey(),
            displayName: name,
            audio: IOSAudioEngine(),
            hooks: AudioHooks()
        )
        controller.setAutoConnect(enabled: auto)
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
        snap = s
        let live = s.call?.live == true
        UIApplication.shared.isIdleTimerDisabled = live || s.group != nil
        UIDevice.current.isProximityMonitoringEnabled = live && !speakerOn
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

    func setSpeaker(_ on: Bool) {
        speakerOn = on
        VoiceIO.shared.setSpeaker(on)
        UIDevice.current.isProximityMonitoringEnabled = (snap?.call?.live == true) && !on
    }
}
