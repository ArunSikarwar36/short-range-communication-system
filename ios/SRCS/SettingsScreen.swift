import SrcsCore
import SwiftUI

/// Me tab: profile, online switch, automatic behaviour, device details and the log.
struct SettingsScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""
    @State private var confirmOffline = false

    var body: some View {
        let online = model.snap?.online == true
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 16) {
                        AvatarBubble(name: model.displayName, size: 64, ring: online ? Ui.good : .gray)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.displayName).font(.system(size: 22, weight: .bold))
                            Text("SRCS · offline communication").font(.footnote).foregroundStyle(Ui.sub)
                            Text("Key \(model.snap?.fingerprint ?? "")").font(.caption2).foregroundStyle(Ui.sub).lineLimit(1)
                        }
                    }
                    .listRowBackground(Color.clear)
                }
                Section {
                    Toggle("Online", isOn: Binding(get: { online }, set: { on in if on { model.setOnline(true) } else { confirmOffline = true } }))
                        .accessibilityIdentifier("onlineToggle")
                    Toggle("Connect automatically", isOn: $model.autoConnect)
                    Toggle("Join walkie-talkies automatically", isOn: $model.autoJoinWalkie)
                } footer: {
                    Text("Connect automatically keeps trying the Android host while this iPhone is on its Wi-Fi; it pauses after someone taps Disconnect. Walkie-talkies started by paired phones are joined without asking (your microphone is only on while you hold the button); group calls always ask.")
                }
                Section("Your name") {
                    HStack {
                        TextField("Name", text: $name).textInputAutocapitalization(.words)
                        Button("Save") { model.setName(name) }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                Section("This device") {
                    LabeledContent("Fingerprint", value: model.snap?.fingerprint ?? "")
                    LabeledContent("Device ID", value: String((model.snap?.deviceId ?? "").prefix(16)))
                    LabeledContent("Opus", value: String(cString: srcs_opus_version()))
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "")
                }
                Section("Diagnostics log") {
                    ForEach(Array((model.snap?.recentLog ?? []).suffix(60).enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(.caption2, design: .monospaced))
                    }
                }
                Section {
                    Text("SRCS keeps working only while it is open or in a call. iOS pauses apps in the background, so it cannot ring for a new call while the app is closed.")
                        .font(.footnote).foregroundStyle(Ui.sub)
                }
            }
            .scrollContentBackground(.hidden)
            .background(Ui.bg.ignoresSafeArea())
            .navigationTitle("Me")
            .onAppear { name = model.displayName }
            .confirmationDialog("Go offline?", isPresented: $confirmOffline, titleVisibility: .visible) {
                Button("Go offline", role: .destructive) { model.setOnline(false) }
            } message: {
                Text("Other phones can no longer call you, message you or reach you on the walkie-talkie until you go online again.")
            }
        }
    }
}
