import SrcsCore
import SwiftUI

struct CallScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let call = model.snap?.call
        VStack(spacing: 24) {
            Spacer()
            Image(systemName: "person.crop.circle.fill").font(.system(size: 96)).foregroundStyle(.secondary)
            Text(call?.peerName ?? "").font(.largeTitle.bold())
            TimelineView(.periodic(from: .now, by: 1)) { ctx in
                Text(statusLine(call, now: ctx.date)).font(.title3).foregroundStyle(.secondary)
                    .accessibilityIdentifier("callStatus")
            }
            Spacer()
            if call?.incomingRinging == true {
                HStack(spacing: 60) {
                    roundButton("Decline", "phone.down.fill", .red) { model.controller.decline() }
                    roundButton("Accept", "phone.fill", .green) { model.answer() }
                }
            } else {
                HStack(spacing: 36) {
                    roundButton(call?.muted == true ? "Unmute" : "Mute", call?.muted == true ? "mic.slash.fill" : "mic.fill", Color(white: 0.25)) {
                        model.controller.setMuted(muted: !(call?.muted ?? false))
                    }
                    roundButton(model.speakerOn ? "Earpiece" : "Speaker", model.speakerOn ? "speaker.wave.3.fill" : "speaker.fill", Color(white: 0.25)) {
                        model.setSpeaker(!model.speakerOn)
                    }
                    roundButton("End", "phone.down.fill", .red) { model.controller.hangUp() }
                }
            }
            Spacer().frame(height: 40)
        }
        .padding()
    }

    private func statusLine(_ c: CallView?, now: Date) -> String {
        guard let c = c else { return "" }
        if c.phase == "ACTIVE" && c.activeSince > 0 {
            let secs = max(0, Int(now.timeIntervalSince1970 - Double(c.activeSince) / 1000))
            return String(format: "%02d:%02d", secs / 60, secs % 60)
        }
        return c.status
    }

    private func roundButton(_ title: String, _ icon: String, _ color: Color, action: @escaping () -> Void) -> some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: icon).font(.title).frame(width: 72, height: 72).background(color, in: Circle()).foregroundStyle(.white)
            }
            .accessibilityLabel(title)
            Text(title).font(.caption)
        }
    }
}
