import SrcsCore
import SwiftUI

/// Nearby radar (same layout as Android, from the shared RadarLayout): this phone in the centre,
/// linked phones on the inner ring, relayed phones on the middle ring, phones waiting for pairing
/// on the outer ring (or at their real GPS bearing and distance when both share a position).
struct RadarView: View {
    @EnvironmentObject var model: AppModel
    /// Called when a paired phone is tapped.
    var onPeer: (RadarDot) -> Void = { _ in }

    private func ringColor(_ d: RadarDot) -> Color {
        if d.ring == RadarRing.linked { return Ui.good }
        if d.ring == RadarRing.relayed { return Ui.cyan }
        return Color.white.opacity(0.6)
    }

    var body: some View {
        let dots = model.snap?.radar ?? []
        let online = model.snap?.online == true
        let talking = model.snap?.group?.iAmTalking == true
        let glow = talking ? Color(red: 1, green: 0.12, blue: 0.18) : Ui.red
        GeometryReader { geo in
            let side = min(geo.size.width, geo.size.height)
            let half = side / 2
            ZStack {
                TimelineView(.animation) { ctx in
                    Canvas { g, size in
                        let c = CGPoint(x: size.width / 2, y: size.height / 2)
                        let r = min(size.width, size.height) / 2
                        func circle(_ rr: CGFloat) -> Path { Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr)) }
                        g.fill(circle(r), with: .radialGradient(
                            Gradient(stops: [
                                .init(color: glow.opacity(online ? 0.55 : 0.25), location: 0),
                                .init(color: glow.opacity(online ? 0.22 : 0.1), location: 0.38),
                                .init(color: glow.opacity(0.06), location: 0.62),
                                .init(color: .clear, location: 0.75),
                            ]), center: c, startRadius: 0, endRadius: r))
                        for f in [0.46, 0.68, 0.88] { g.stroke(circle(r * f), with: .color(Color(red: 1, green: 0.47, blue: 0.47).opacity(0.25)), lineWidth: 1) }
                        guard online else { return }
                        let t = ctx.date.timeIntervalSinceReferenceDate
                        for k in 0..<2 {
                            let p = (t / 2.8 + Double(k) * 0.5).truncatingRemainder(dividingBy: 1)
                            g.stroke(circle(r * (0.3 + 0.7 * p)), with: .color(glow.opacity((1 - p) * 0.6)), lineWidth: 2)
                        }
                        if dots.isEmpty {
                            // Searching: a rotating sweep.
                            let a = Angle(radians: (t / 3).truncatingRemainder(dividingBy: 1) * 2 * .pi)
                            var wedge = Path()
                            wedge.move(to: c)
                            wedge.addArc(center: c, radius: r * 0.92, startAngle: a - .degrees(70), endAngle: a, clockwise: false)
                            wedge.closeSubpath()
                            g.fill(wedge, with: .conicGradient(Gradient(colors: [.clear, glow.opacity(0.45)]), center: c, angle: a - .degrees(70)))
                        }
                    }
                }
                AvatarBubble(name: model.displayName, size: dots.isEmpty ? 76 : 64, ring: .white)
                    .shadow(color: glow.opacity(0.6), radius: 18)
                    .position(x: half, y: half)
                ForEach(dots, id: \.key) { d in
                    let rad = (Double(d.angleDeg) - 90) * .pi / 180
                    let x = half + half * Double(d.radius) * cos(rad)
                    let y = half + half * Double(d.radius) * sin(rad)
                    VStack(spacing: 3) {
                        AvatarBubble(name: d.name, size: 48, ring: ringColor(d))
                        Text(label(d)).font(.system(size: 11, weight: .medium)).foregroundStyle(.white).lineLimit(1)
                            .padding(.horizontal, 6).padding(.vertical, 1)
                            .background(Capsule().fill(Color.black.opacity(0.55)))
                            .frame(maxWidth: 88)
                    }
                    .frame(width: 88)
                    .position(x: x, y: y + 10)
                    .onTapGesture { if !d.peerId.isEmpty { onPeer(d) } }
                    .accessibilityLabel(d.name)
                }
            }
            .frame(width: side, height: side)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func label(_ d: RadarDot) -> String {
        let n = String(d.name.prefix(12))
        if d.distanceM >= 0 { return n + " · " + (d.distanceM < 1000 ? "\(Int((d.distanceM / 5).rounded()) * 5) m" : String(format: "%.1f km", d.distanceM / 1000)) }
        if d.ring == RadarRing.relayed { return n + " · relay" }
        if d.ring == RadarRing.nearby { return n + " · new" }
        return n
    }
}
