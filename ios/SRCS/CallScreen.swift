import SrcsCore
import SwiftUI

struct CallScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let call = model.snap?.call
        ZStack {
            RadialGradient(colors: [Color(red: 0.23, green: 0.10, blue: 0.36), Color(red: 0.09, green: 0.05, blue: 0.15), Color(red: 0.03, green: 0.03, blue: 0.05)],
                           center: UnitPoint(x: 0.5, y: 0.38), startRadius: 0, endRadius: 600)
                .ignoresSafeArea()
            VStack(spacing: 0) {
                HStack(spacing: 6) {
                    Image(systemName: "lock.fill").font(.system(size: 12)).foregroundStyle(Ui.good)
                    Text("End-to-end encrypted").font(.system(size: 12, weight: .semibold)).foregroundStyle(Ui.text)
                }
                .padding(.horizontal, 12).padding(.vertical, 7).glass(16)
                .padding(.top, 20)
                Spacer()
                ZStack {
                    TimelineView(.animation) { ctx in
                        Canvas { g, size in
                            let c = CGPoint(x: size.width / 2, y: size.height / 2)
                            let r = min(size.width, size.height) / 2
                            let t = ctx.date.timeIntervalSinceReferenceDate
                            for k in 0..<2 {
                                let p = (t / 2.4 + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                                let rr = r * (0.5 + 0.5 * p)
                                g.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr)),
                                         with: .color(Ui.purple.opacity((1 - p) * 0.6)), lineWidth: 2)
                            }
                        }
                    }
                    .frame(width: 300, height: 300)
                    Circle().fill(LinearGradient(colors: [Ui.purple, Ui.pink], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 150, height: 150)
                        .shadow(color: Ui.purple.opacity(0.6), radius: 30)
                    AvatarBubble(name: call?.peerName ?? "?", size: 142, ring: .clear)
                }
                Text(call?.peerName ?? "").font(.system(size: 30, weight: .bold)).foregroundStyle(Ui.text)
                TimelineView(.periodic(from: .now, by: 1)) { ctx in
                    Text(statusLine(call, now: ctx.date)).font(.system(size: 15)).foregroundStyle(Color(red: 0.79, green: 0.74, blue: 0.88))
                        .accessibilityIdentifier("callStatus")
                }
                .padding(.top, 4)
                Spacer()
                HStack {
                    if call?.incomingRinging == true {
                        roundButton("Decline", "phone.down.fill", Ui.red) { model.controller.decline() }
                        roundButton("Accept", "phone.fill", Color(red: 0.13, green: 0.77, blue: 0.37)) { model.answer() }
                    } else {
                        roundButton(call?.muted == true ? "Unmute" : "Mute", call?.muted == true ? "mic.slash.fill" : "mic.fill",
                                    call?.muted == true ? Ui.warn : Color.white.opacity(0.12)) {
                            model.controller.setMuted(muted: !(call?.muted ?? false))
                        }
                        roundButton(model.speakerOn ? "Speaker" : "Earpiece", model.speakerOn ? "speaker.wave.3.fill" : "ear",
                                    .white, iconColor: Color(red: 0.1, green: 0.06, blue: 0.19)) {
                            model.setSpeaker(!model.speakerOn)
                        }
                        roundButton("End", "phone.down.fill", Ui.red) { model.controller.hangUp() }
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 18)
                .glass(32, fill: Color.white.opacity(0.07), line: Color.white.opacity(0.1))
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
        }
    }

    private func statusLine(_ c: CallView?, now: Date) -> String {
        guard let c = c else { return "" }
        if c.phase == "ACTIVE" && c.activeSince > 0 {
            let secs = max(0, Int(now.timeIntervalSince1970 - Double(c.activeSince) / 1000))
            return String(format: "%02d:%02d", secs / 60, secs % 60)
        }
        return c.status
    }

    private func roundButton(_ title: String, _ icon: String, _ color: Color, iconColor: Color = .white, action: @escaping () -> Void) -> some View {
        VStack(spacing: 6) {
            Button(action: action) {
                Image(systemName: icon).font(.system(size: 24)).frame(width: 62, height: 62).background(color, in: Circle()).foregroundStyle(iconColor)
            }
            .accessibilityLabel(title)
            Text(title).font(.caption).foregroundStyle(Color(red: 0.79, green: 0.74, blue: 0.88))
        }
        .frame(maxWidth: .infinity)
    }
}
