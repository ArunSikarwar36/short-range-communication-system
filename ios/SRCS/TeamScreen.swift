import SrcsCore
import SwiftUI

/// Team tab: purple header, stats, nearby phones with chat / call buttons, the current group,
/// DJ and location sharing.
struct TeamScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let s = model.snap
        let peers = s?.peers ?? []
        let reachable = peers.filter { $0.reachable }
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 14) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text("Team").font(.system(size: 13)).foregroundStyle(Color(red: 0.66, green: 0.61, blue: 0.77))
                            Text(s?.group?.name ?? "Nearby").font(.system(size: 28, weight: .bold)).foregroundStyle(Ui.text)
                        }
                        HStack(spacing: 8) {
                            stat("\(peers.count)", "paired", Ui.text)
                            stat("\(reachable.count)", "connected", Ui.good)
                            stat("\((s?.radar ?? []).filter { $0.peerId.isEmpty }.count)", "pairing", Ui.text)
                        }
                    }
                    .listRowBackground(Color.clear)
                    .listRowInsets(EdgeInsets(top: 8, leading: 4, bottom: 8, trailing: 4))
                }
                Section("Phones") {
                    if peers.isEmpty {
                        Text("Connect to a phone on the Home tab and confirm the pairing code to add it here.")
                            .font(.callout).foregroundStyle(Ui.sub)
                    }
                    ForEach(peers, id: \.id) { p in
                        HStack(spacing: 12) {
                            ZStack(alignment: .bottomTrailing) {
                                AvatarBubble(name: p.name, size: 44, ring: .clear)
                                if p.reachable { Circle().fill(Ui.good).frame(width: 12, height: 12).overlay(Circle().stroke(Color.black, lineWidth: 2)) }
                            }
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).font(.system(size: 15, weight: .semibold)).foregroundStyle(Ui.text)
                                Text(peerStatus(p)).font(.caption).foregroundStyle(Ui.sub).lineLimit(1)
                            }
                            Spacer()
                            NavigationLink {
                                ChatScreen(peerId: p.id, title: p.name)
                            } label: {
                                ZStack(alignment: .topTrailing) {
                                    Image(systemName: "bubble.left.fill").foregroundStyle(Ui.amber)
                                    if p.unread > 0 {
                                        Text("\(p.unread)").font(.caption2).padding(3).background(Color.red, in: Circle()).offset(x: 8, y: -8)
                                    }
                                }
                            }
                            .frame(width: 44)
                            .accessibilityIdentifier("chat-\(p.name)")
                            Button {
                                model.call(p.id)
                            } label: {
                                Image(systemName: "phone.fill").foregroundStyle(Ui.good)
                                    .frame(width: 38, height: 38).background(Circle().fill(Ui.good.opacity(0.16)))
                            }
                            .buttonStyle(.borderless)
                            .disabled(!p.reachable)
                            .accessibilityIdentifier("call-\(p.name)")
                        }
                        .swipeActions {
                            Button(role: .destructive) { model.controller.unpair(peerId: p.id) } label: { Text("Unpair") }
                        }
                    }
                }
                .listRowBackground(Color(red: 0.08, green: 0.07, blue: 0.12).opacity(0.78))
                if let g = s?.group {
                    Section {
                        NavigationLink {
                            GroupScreen()
                        } label: {
                            Label("\(g.isPtt ? "Walkie-talkie" : "Group call") '\(g.name)' · \(g.members.count) members", systemImage: "person.3.fill")
                        }
                        .accessibilityIdentifier("teamGroup")
                    }
                    .listRowBackground(Color(red: 0.08, green: 0.07, blue: 0.12).opacity(0.78))
                } else if !reachable.isEmpty {
                    Section {
                        Button { model.startWalkieTalkie() } label: { PrimaryPill(title: "Start walkie-talkie", height: 50) }
                            .buttonStyle(.plain)
                    }
                    .listRowBackground(Color.clear)
                }
                if s?.online == true {
                    LocationSection()
                        .listRowBackground(Color(red: 0.08, green: 0.07, blue: 0.12).opacity(0.78))
                }
                if !reachable.isEmpty {
                    Section {
                        Button(role: .destructive) { model.controller.disconnectAll() } label: { Text("Disconnect from all phones") }
                    }
                    .listRowBackground(Color(red: 0.08, green: 0.07, blue: 0.12).opacity(0.78))
                }
            }
            .scrollContentBackground(.hidden)
            .background(
                RadialGradient(colors: [Ui.purpleDeep, Color(red: 0.04, green: 0.05, blue: 0.08), Color(red: 0.03, green: 0.035, blue: 0.05)],
                               center: .top, startRadius: 0, endRadius: 700).ignoresSafeArea())
            .toolbar(.hidden, for: .navigationBar)
        }
    }

    private func stat(_ value: String, _ label: String, _ color: Color) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(value).font(.system(size: 22, weight: .bold)).foregroundStyle(color)
            Text(label).font(.system(size: 12)).foregroundStyle(Ui.sub)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .glass(18, fill: Color.white.opacity(0.06), line: Color.white.opacity(0.08))
    }

    private func peerStatus(_ p: PeerView) -> String {
        if model.snap?.online != true { return "Offline" }
        if !p.reachable { return "Not in range" }
        if p.direct { return p.lastMessage.isEmpty ? "Connected directly" : p.lastMessage }
        return "Via relay · \(p.hops) hops"
    }
}

/// Chats tab: one row per paired phone with the last message.
struct ChatsScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let peers = model.snap?.peers ?? []
        NavigationStack {
            List {
                if peers.isEmpty {
                    Text("No conversations yet. Pair with a phone first.").foregroundStyle(Ui.sub)
                }
                ForEach(peers, id: \.id) { p in
                    NavigationLink {
                        ChatScreen(peerId: p.id, title: p.name)
                    } label: {
                        HStack(spacing: 12) {
                            AvatarBubble(name: p.name, size: 44, ring: .clear)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(p.name).font(.system(size: 15, weight: .semibold))
                                Text(p.lastMessage.isEmpty ? "No messages yet" : p.lastMessage).font(.caption).foregroundStyle(Ui.sub).lineLimit(1)
                            }
                            Spacer()
                            if p.unread > 0 {
                                Text("\(p.unread)").font(.caption.bold()).padding(.horizontal, 7).padding(.vertical, 3).background(Capsule().fill(Ui.red))
                            }
                        }
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Ui.bg.ignoresSafeArea())
            .navigationTitle("Chats")
        }
    }
}
