import SrcsCore
import SwiftUI

struct HomeScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var address = ""

    var body: some View {
        NavigationStack {
            List {
                statusSection
                if model.snap?.online == true {
                    Section("Nearby radar") { RadarView() }
                }
                if let g = model.snap?.group {
                    Section {
                        NavigationLink {
                            GroupScreen()
                        } label: {
                            Label("\(g.isPtt ? "Walkie-talkie" : "Group call") '\(g.name)' · \(g.members.count) members", systemImage: "person.3.fill")
                        }
                        .accessibilityIdentifier("openGroup")
                    }
                }
                invitesSection
                if model.snap?.online == true { connectSection }
                peersSection
            }
            .navigationTitle("SRCS")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    NavigationLink {
                        SettingsScreen()
                    } label: {
                        Image(systemName: "gearshape")
                    }
                    .accessibilityIdentifier("settings")
                }
            }
            .alert(pairingTitle, isPresented: pairingShown, presenting: pendingPairing) { p in
                Button("Codes match") { model.controller.confirmPairing(linkId: p.linkId, accept: true) }
                Button("Cancel", role: .cancel) { model.controller.confirmPairing(linkId: p.linkId, accept: false) }
            } message: { p in
                Text("Check that the other phone shows the same code:\n\n\(p.code)\n\nIf the codes differ, someone may be intercepting the connection. Tap Cancel.")
            }
        }
    }

    // MARK: - Sections

    private var statusSection: some View {
        Section("Status") {
            let online = model.snap?.online == true
            let linked = Int(model.snap?.linkCount ?? 0)
            HStack(spacing: 12) {
                Circle().fill(online ? (linked > 0 ? Color.green : Color.orange) : Color.gray).frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(online ? (linked > 0 ? "Online · \(linked) linked" : "Online · not linked") : "Offline")
                        .font(.headline)
                    Text(online ? "Listening on this Wi-Fi network. No internet is used." : "Go online to talk to nearby phones.")
                        .font(.caption).foregroundStyle(.secondary)
                }
                Spacer()
                if model.snap?.busy == true {
                    ProgressView()
                } else {
                    Button(online ? "Go offline" : "Go online") { model.setOnline(!online) }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("onlineButton")
                }
            }
        }
    }

    @ViewBuilder private var invitesSection: some View {
        if let invites = model.snap?.invites, !invites.isEmpty {
            Section("Invitations") {
                ForEach(invites, id: \.groupId) { inv in
                    VStack(alignment: .leading, spacing: 8) {
                        Text("\(inv.fromName) invited you to '\(inv.name)'")
                        Text(inv.isPtt ? "Walkie-talkie" : "Group call").font(.caption).foregroundStyle(.secondary)
                        HStack {
                            Button("Decline") { model.controller.declineInvite(groupId: inv.groupId) }.buttonStyle(.bordered)
                            Button("Join") { model.joinGroup(inv.groupId) }.buttonStyle(.borderedProminent)
                        }
                    }
                }
            }
        }
    }

    private var connectSection: some View {
        Section {
            Button {
                model.connect(to: "192.168.49.1")
            } label: {
                Label("Connect to Android host", systemImage: "antenna.radiowaves.left.and.right")
            }
            .accessibilityIdentifier("connectHost")
            HStack {
                TextField("Other address (host:port)", text: $address)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .accessibilityIdentifier("addressField")
                Button("Connect") { model.connect(to: address.isEmpty ? "192.168.49.1" : address) }
                    .accessibilityIdentifier("connectButton")
            }
            Toggle("Connect automatically", isOn: $model.autoConnect)
        } header: {
            Text("Join an Android group")
        } footer: {
            Text("1. On the Android phone: Host a group → Add an iPhone.\n2. Point the iPhone Camera at its QR code and tap Join network.\n3. Come back here; SRCS connects to the host (192.168.49.1) and asks you to compare a pairing code.")
        }
    }

    private var peersSection: some View {
        Section("Paired devices") {
            let peers = model.snap?.peers ?? []
            if peers.isEmpty {
                Text("Connect to a phone and confirm the pairing code to add it here.")
                    .font(.callout).foregroundStyle(.secondary)
            }
            ForEach(peers, id: \.id) { p in
                HStack(spacing: 12) {
                    Circle().fill(p.reachable ? Color.green : Color.gray).frame(width: 10, height: 10)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.name)
                        Text(peerStatus(p)).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    NavigationLink {
                        ChatScreen(peerId: p.id, title: p.name)
                    } label: {
                        ZStack(alignment: .topTrailing) {
                            Image(systemName: "bubble.left.fill")
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
                        Image(systemName: "phone.fill")
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
    }

    private func peerStatus(_ p: PeerView) -> String {
        if model.snap?.online != true { return "Offline" }
        if !p.reachable { return "Not in range" }
        if p.direct { return p.lastMessage.isEmpty ? "Connected directly" : p.lastMessage }
        return "Via relay · \(p.hops) hops"
    }

    // MARK: - Pairing

    private var pendingPairing: PairingView? {
        model.snap?.pairings.first { !$0.waitingForOther }
    }

    private var pairingTitle: String { "Pair with \(pendingPairing?.name ?? "device")?" }

    private var pairingShown: Binding<Bool> {
        Binding(get: { pendingPairing != nil }, set: { _ in })
    }
}
