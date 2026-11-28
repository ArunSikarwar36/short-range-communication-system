import SrcsCore
import SwiftUI

@main
struct SRCSApp: App {
    @StateObject private var model = AppModel()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @EnvironmentObject var model: AppModel
    @State private var tab = "home"

    var body: some View {
        ZStack(alignment: .bottom) {
            if model.onboarded {
                TabView(selection: $tab) {
                    HomeScreen().tabItem { Label("Home", systemImage: "waveform") }.tag("home")
                    TeamScreen().tabItem { Label("Team", systemImage: "person.2") }.tag("team")
                        .badge(model.snap?.invites.count ?? 0)
                    NavigationStack { MapScreen() }.tabItem { Label("Map", systemImage: "map") }.tag("map")
                    ChatsScreen().tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }.tag("chats")
                        .badge(Int((model.snap?.peers ?? []).reduce(0) { $0 + $1.unread }))
                    SettingsScreen().tabItem { Label("Me", systemImage: "person.crop.circle") }.tag("me")
                }
                .tint(tab == "team" ? Ui.purple : (tab == "home" ? Ui.redLight : Ui.cyan))
            } else {
                OnboardingScreen()
            }
            if let text = model.notice {
                Text(text)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 70)
                    .transition(.opacity)
                    .accessibilityIdentifier("notice")
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.notice)
        .alert(pairingTitle, isPresented: pairingShown, presenting: pendingPairing) { p in
            Button("Codes match") { model.controller.confirmPairing(linkId: p.linkId, accept: true) }
            Button("Cancel", role: .cancel) { model.controller.confirmPairing(linkId: p.linkId, accept: false) }
        } message: { p in
            Text("Check that the other phone shows the same code:\n\n\(p.code)\n\nIf the codes differ, someone may be intercepting the connection. Tap Cancel.")
        }
        .fullScreenCover(isPresented: Binding(
            get: { model.snap?.call?.live == true },
            set: { _ in }
        )) {
            CallScreen().environmentObject(model)
        }
    }

    private var pendingPairing: PairingView? {
        model.snap?.pairings.first { !$0.waitingForOther }
    }

    private var pairingTitle: String { "Pair with \(pendingPairing?.name ?? "device")?" }

    private var pairingShown: Binding<Bool> {
        Binding(get: { pendingPairing != nil }, set: { _ in })
    }
}

struct OnboardingScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var name = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Spacer()
            Text("SRCS").font(.system(size: 44, weight: .bold))
            Text("Short Range Communication System").font(.title3).foregroundStyle(.secondary)
            Text("Talk to nearby phones without mobile network, internet or Wi-Fi router. An Android phone running SRCS hosts the group; this iPhone joins its Wi-Fi.")
                .foregroundStyle(.secondary)
            TextField("Your name (\(model.displayName))", text: $name)
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.words)
                .accessibilityIdentifier("nameField")
            Button {
                model.finishOnboarding(name: name.isEmpty ? model.displayName : name)
            } label: {
                Text("Get started").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .accessibilityIdentifier("getStarted")
            Spacer()
        }
        .padding(24)
    }
}
