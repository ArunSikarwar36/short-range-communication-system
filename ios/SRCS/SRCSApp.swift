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

    var body: some View {
        ZStack(alignment: .bottom) {
            if model.onboarded {
                HomeScreen()
            } else {
                OnboardingScreen()
            }
            if let text = model.notice {
                Text(text)
                    .font(.subheadline)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial, in: Capsule())
                    .padding(.bottom, 24)
                    .transition(.opacity)
                    .accessibilityIdentifier("notice")
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.notice)
        .fullScreenCover(isPresented: Binding(
            get: { model.snap?.call?.live == true },
            set: { _ in }
        )) {
            CallScreen().environmentObject(model)
        }
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
