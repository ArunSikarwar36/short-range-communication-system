import SrcsCore
import SwiftUI

struct SettingsScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""

    var body: some View {
        List {
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
                    .font(.footnote).foregroundStyle(.secondary)
            }
        }
        .navigationTitle("Settings")
        .onAppear { name = model.displayName }
    }
}
