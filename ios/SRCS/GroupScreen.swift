import SrcsCore
import SwiftUI

struct GroupScreen: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pressing = false

    var body: some View {
        if let g = model.snap?.group {
            VStack(spacing: 20) {
                List {
                    Section(g.isPtt ? "Walkie-talkie" : "Group call") {
                        ForEach(g.members, id: \.id) { m in
                            HStack {
                                Image(systemName: m.talking ? "waveform" : "person.fill")
                                    .foregroundStyle(m.talking ? Color.green : Color.secondary)
                                Text(m.isMe ? "\(m.name) (you)" : m.name)
                                Spacer()
                                if m.isHost { Text("Host").font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                    if g.status == "ACTIVE" {
                        DjSection(isHost: g.isHost)
                    }
                    if g.status != "ACTIVE" {
                        Text(g.status == "JOINING" ? "Joining…" : "Host lost — reconnecting…").foregroundStyle(.orange)
                    }
                }
                .frame(maxHeight: 460)

                if g.isPtt {
                    Text(pttLine(g)).font(.headline).accessibilityIdentifier("pttStatus")
                    Circle()
                        .fill(g.iAmTalking ? Color.green : (g.talker.isEmpty ? Color.accentColor : Color.gray))
                        .frame(width: 170, height: 170)
                        .overlay(Text(g.iAmTalking ? "Talking" : "Hold to talk").font(.title3.bold()).foregroundStyle(.white))
                        .scaleEffect(pressing ? 0.95 : 1)
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { _ in
                                    if !pressing {
                                        pressing = true
                                        model.controller.pressTalk()
                                    }
                                }
                                .onEnded { _ in
                                    pressing = false
                                    model.controller.releaseTalk()
                                }
                        )
                        .accessibilityIdentifier("pttButton")
                } else {
                    Button {
                        model.controller.setMuted(muted: !(model.snap?.call?.muted ?? false))
                    } label: {
                        Label("Mute", systemImage: "mic.slash")
                    }
                    .buttonStyle(.bordered)
                }
                Button(role: .destructive) {
                    model.controller.leaveGroup()
                    dismiss()
                } label: {
                    Text(g.isHost ? "End group" : "Leave group").frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .padding(.horizontal)
                Spacer()
            }
            .navigationTitle(g.name)
            .onAppear { model.setSpeaker(true) }
            .onDisappear {
                if pressing {
                    pressing = false
                    model.controller.releaseTalk()
                }
            }
        } else {
            Text("Not in a group").foregroundStyle(.secondary)
        }
    }

    private func pttLine(_ g: GroupView) -> String {
        if g.iAmTalking { return "You are talking" }
        if g.requesting { return "Requesting the channel…" }
        if !g.talker.isEmpty { return "\(g.talker) is talking" }
        if g.denied { return "Someone else is talking" }
        return "Channel free"
    }
}
