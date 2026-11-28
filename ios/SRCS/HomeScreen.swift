import SrcsCore
import SwiftUI

/// Home: the radar with friends as avatar bubbles, one glossy main button and a channel strip.
/// Three states: offline ("Go online"), not linked (join an Android phone) and connected.
struct HomeScreen: View {
    @EnvironmentObject var model: AppModel
    @State private var address = ""
    @State private var peerMenu: RadarDot?
    @State private var chatTarget: PeerTarget?
    @State private var openGroup = false
    @State private var confirmDisconnect = false
    @State private var pressing = false

    var body: some View {
        let s = model.snap
        let online = s?.online == true
        let reachable = (s?.peers ?? []).filter { $0.reachable }
        let connected = online && (!reachable.isEmpty || s?.group != nil)
        NavigationStack {
            ZStack {
                GridBackdrop()
                ScrollView {
                    VStack(spacing: 12) {
                        topBar(online: online, reachable: reachable.count)
                        invites
                        sosBanners
                        RadarView { d in peerMenu = d }
                            .frame(height: 340)
                        if !online {
                            offline
                        } else if !connected {
                            notLinked
                        } else {
                            connectedControls(reachable: reachable)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 24)
                }
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(isPresented: Binding(get: { chatTarget != nil }, set: { if !$0 { chatTarget = nil } })) {
                if let t = chatTarget { ChatScreen(peerId: t.id, title: t.name) }
            }
            .navigationDestination(isPresented: $openGroup) { GroupScreen() }
            .confirmationDialog(peerMenu?.name ?? "", isPresented: Binding(get: { peerMenu != nil }, set: { if !$0 { peerMenu = nil } }), titleVisibility: .visible) {
                if let d = peerMenu {
                    Button("Call") { model.call(d.peerId) }
                    Button("Message") { chatTarget = PeerTarget(id: d.peerId, name: d.name) }
                    if s?.group == nil { Button("Start walkie-talkie") { model.startWalkieTalkie() } }
                    Button("Disconnect", role: .destructive) { model.controller.disconnectAll() }
                }
            } message: {
                if let d = peerMenu { Text(d.ring == RadarRing.linked ? "Connected directly" : "Reached through another phone") }
            }
            .confirmationDialog("Disconnect?", isPresented: $confirmDisconnect, titleVisibility: .visible) {
                Button("Disconnect", role: .destructive) { model.controller.disconnectAll() }
            } message: {
                Text("Ends the walkie-talkie and the link with \(reachable.map(\.name).joined(separator: ", ")). They are told, so nobody has to disconnect twice.")
            }
        }
    }

    // MARK: - Parts

    private func topBar(online: Bool, reachable: Int) -> some View {
        let linked = Int(model.snap?.linkCount ?? 0)
        let text: String
        let dot: Color
        if model.snap?.busy == true { text = "Starting…"; dot = Ui.warn }
        else if !online { text = "Offline"; dot = .gray }
        else if reachable > 0 { text = "Online · \(reachable) nearby"; dot = Ui.good }
        else if linked > 0 { text = "Online · pairing"; dot = Ui.warn }
        else { text = "Online · not linked"; dot = Ui.warn }
        return HStack {
            Text("SRCS").font(.system(size: 26, weight: .bold)).kerning(3).foregroundStyle(Ui.text)
            Spacer()
            StatusChip(text: text, dot: dot)
        }
        .padding(.top, 8)
    }

    @ViewBuilder private var invites: some View {
        ForEach(model.snap?.invites ?? [], id: \.groupId) { inv in
            VStack(alignment: .leading, spacing: 10) {
                Text(inv.isPtt ? "\(inv.fromName) started a walkie-talkie" : "\(inv.fromName) invited you to a group call")
                    .font(.system(size: 15, weight: .bold)).foregroundStyle(Ui.text)
                HStack(spacing: 10) {
                    Button { model.joinGroup(inv.groupId) } label: { PrimaryPill(title: "Join", colors: [Ui.teal, Color(red: 0.05, green: 0.65, blue: 0.63)], height: 44) }
                    Button { model.controller.declineInvite(groupId: inv.groupId) } label: { GlassLabel(title: "Not now") }
                }
            }
            .padding(14)
            .glass(18, fill: Ui.teal.opacity(0.18), line: Ui.teal.opacity(0.5))
        }
    }

    @ViewBuilder private var sosBanners: some View {
        ForEach(model.snap?.sos ?? [], id: \.alertId) { a in
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("SOS from \(a.name)").font(.headline).foregroundStyle(Color(red: 1, green: 0.54, blue: 0.56))
                    if !a.note.isEmpty { Text(a.note).font(.caption).foregroundStyle(Ui.text) }
                    Text(a.hasFix ? (a.distanceText.isEmpty ? String(format: "%.5f, %.5f", a.lat, a.lon) : a.distanceText) : "No GPS position")
                        .font(.caption).foregroundStyle(Ui.sub)
                }
                Spacer()
                if a.hasFix { Button("Map") { MapLink.open(lat: a.lat, lon: a.lon, label: "SOS \(a.name)") } }
                Button("Dismiss") { model.controller.dismissSos(alertId: a.alertId) }
            }
            .padding(14)
            .glass(18, fill: Ui.red.opacity(0.2), line: Ui.red.opacity(0.5))
        }
    }

    private var offline: some View {
        VStack(spacing: 14) {
            Text("You're offline").font(.system(size: 22, weight: .bold)).foregroundStyle(Ui.text)
            Text("Go online to talk to nearby phones. Calls and messages never use the internet.")
                .font(.system(size: 14)).foregroundStyle(Ui.sub).multilineTextAlignment(.center)
            Button { model.setOnline(true) } label: { PrimaryPill(title: model.snap?.busy == true ? "Starting…" : "Go online", icon: "dot.radiowaves.left.and.right") }
                .accessibilityIdentifier("onlineButton")
        }
    }

    private var notLinked: some View {
        VStack(spacing: 12) {
            Text("Join an Android phone").font(.system(size: 22, weight: .bold)).foregroundStyle(Ui.text)
            Text("On the Android phone tap Host a group → Add an iPhone and scan its QR code with the Camera. Then come back here; SRCS connects by itself.")
                .font(.system(size: 14)).foregroundStyle(Ui.sub).multilineTextAlignment(.center)
            Button { model.connect(to: "192.168.49.1") } label: { PrimaryPill(title: "Connect to Android host", icon: "antenna.radiowaves.left.and.right") }
                .accessibilityIdentifier("connectHost")
            HStack(spacing: 10) {
                TextField("Other address (host:port)", text: $address)
                    .keyboardType(.numbersAndPunctuation)
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(.horizontal, 14).frame(height: 44)
                    .glass(22, fill: Color(red: 0.06, green: 0.09, blue: 0.12).opacity(0.85))
                    .accessibilityIdentifier("addressField")
                Button("Connect") { model.connect(to: address.isEmpty ? "192.168.49.1" : address) }
                    .font(.system(size: 14, weight: .semibold))
                    .accessibilityIdentifier("connectButton")
            }
            Toggle("Connect automatically", isOn: $model.autoConnect).foregroundStyle(Ui.text).tint(Ui.good)
        }
    }

    private func connectedControls(reachable: [PeerView]) -> some View {
        let g = model.snap?.group
        return VStack(spacing: 10) {
            HStack(spacing: 12) {
                Button {
                    if g != nil { model.controller.setMuted(muted: !(model.snap?.call?.muted ?? false)) } else { model.show("Start a walkie-talkie first") }
                } label: {
                    Image(systemName: "mic.fill").font(.system(size: 22)).foregroundStyle(Ui.text)
                        .frame(width: 60, height: 60).glass(30, fill: Color(red: 0.12, green: 0.15, blue: 0.2).opacity(0.9))
                }
                if let g, g.isPtt {
                    PrimaryPill(title: pttLabel(g), height: 60)
                        .scaleEffect(pressing ? 0.97 : 1)
                        .gesture(DragGesture(minimumDistance: 0)
                            .onChanged { _ in if !pressing { pressing = true; model.controller.pressTalk() } }
                            .onEnded { _ in pressing = false; model.controller.releaseTalk() })
                        .accessibilityIdentifier("pttButton")
                } else if let g {
                    Button { openGroup = true } label: { PrimaryPill(title: "Group call · \(g.members.count)", height: 60) }
                } else {
                    Button { model.startWalkieTalkie() } label: { PrimaryPill(title: "Start walkie-talkie", height: 60) }
                        .accessibilityIdentifier("startWalkie")
                }
            }
            HStack(spacing: 10) {
                Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 14)).foregroundStyle(Ui.good)
                    .frame(width: 30, height: 30).background(Circle().fill(Ui.good.opacity(0.15)))
                VStack(alignment: .leading, spacing: 2) {
                    Text(g.map { "\($0.name) · \($0.isPtt ? "walkie-talkie" : "group call")" } ?? "\(reachable.count) phone\(reachable.count == 1 ? "" : "s") connected")
                        .font(.system(size: 14, weight: .semibold)).foregroundStyle(Ui.text).lineLimit(1)
                    Text(g.map { $0.members.map(\.name).joined(separator: ", ") } ?? reachable.map(\.name).joined(separator: ", "))
                        .font(.system(size: 12)).foregroundStyle(Ui.sub).lineLimit(1)
                }
                Spacer()
                Button { confirmDisconnect = true } label: {
                    Text("Disconnect").font(.system(size: 13, weight: .semibold)).foregroundStyle(Color(red: 1, green: 0.54, blue: 0.56))
                        .padding(.horizontal, 12).padding(.vertical, 7).background(Capsule().fill(Ui.red.opacity(0.14)))
                }
                .accessibilityIdentifier("disconnect")
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            .glass(16, fill: Color(red: 0.06, green: 0.09, blue: 0.12).opacity(0.85), line: Color.white.opacity(0.08))
            .contentShape(Rectangle())
            .onTapGesture { if g != nil { openGroup = true } }
            .accessibilityIdentifier("openGroup")
        }
        .onDisappear { if pressing { pressing = false; model.controller.releaseTalk() } }
    }

    private func pttLabel(_ g: GroupView) -> String {
        if g.iAmTalking { return "Talking…" }
        if !g.talker.isEmpty { return "\(g.talker) is talking" }
        if g.members.count < 2 { return "Waiting for others…" }
        return "Hold to talk"
    }
}

struct PeerTarget: Hashable {
    let id: String
    let name: String
}
