import SrcsCore
import SwiftUI

/// Nearby radar (same layout as Android, from the shared RadarLayout): this phone in the centre,
/// linked phones on the inner ring, relayed phones on the middle ring, phones waiting for pairing
/// on the outer ring. Rings show how a phone is reached, not a measured distance.
struct RadarView: View {
    @EnvironmentObject var model: AppModel
    @State private var selected: RadarDot?

    private static let accent = Color(red: 0.13, green: 0.78, blue: 0.90)
    private static let accentDim = Color(red: 0.11, green: 0.44, blue: 0.51)
    private static let good = Color(red: 0.24, green: 0.86, blue: 0.59)

    private func color(_ d: RadarDot) -> Color {
        if d.ring == RadarRing.linked { return Self.good }
        if d.ring == RadarRing.relayed { return Self.accent }
        return .gray
    }

    var body: some View {
        let dots = model.snap?.radar ?? []
        VStack(spacing: 10) {
            GeometryReader { geo in
                let side = min(geo.size.width, geo.size.height)
                let half = side / 2
                ZStack {
                    TimelineView(.animation) { ctx in
                        Canvas { g, size in
                            let c = CGPoint(x: size.width / 2, y: size.height / 2)
                            let r = min(size.width, size.height) / 2
                            g.fill(Path(ellipseIn: CGRect(x: c.x - r, y: c.y - r, width: 2 * r, height: 2 * r)),
                                   with: .radialGradient(Gradient(colors: [Self.accent.opacity(0.16), .clear]), center: c, startRadius: 0, endRadius: r))
                            for f in [0.46, 0.68, 0.88] {
                                let rr = r * f
                                g.stroke(Path(ellipseIn: CGRect(x: c.x - rr, y: c.y - rr, width: 2 * rr, height: 2 * rr)), with: .color(Self.accentDim.opacity(0.7)), lineWidth: 1)
                            }
                            let t = ctx.date.timeIntervalSinceReferenceDate
                            let pulse = t.truncatingRemainder(dividingBy: 2.8) / 2.8
                            let pr = r * pulse
                            g.stroke(Path(ellipseIn: CGRect(x: c.x - pr, y: c.y - pr, width: 2 * pr, height: 2 * pr)), with: .color(Self.accent.opacity((1 - pulse) * 0.45)), lineWidth: 2)
                            let a = (t.truncatingRemainder(dividingBy: 6) / 6) * 2 * .pi - .pi / 2
                            var line = Path()
                            line.move(to: c)
                            line.addLine(to: CGPoint(x: c.x + r * cos(a), y: c.y + r * sin(a)))
                            g.stroke(line, with: .color(Self.accent.opacity(0.45)), lineWidth: 2)
                        }
                    }
                    Circle().fill(Self.accent).frame(width: 52, height: 52)
                        .overlay(Text("You").font(.footnote.bold()).foregroundColor(.black))
                        .position(x: half, y: half)
                    ForEach(dots, id: \.key) { d in
                        let rad = (Double(d.angleDeg) - 90) * .pi / 180
                        let x = half + half * Double(d.radius) * cos(rad)
                        let y = half + half * Double(d.radius) * sin(rad)
                        VStack(spacing: 2) {
                            Circle().fill(color(d).opacity(0.22)).frame(width: 38, height: 38)
                                .overlay(Circle().stroke(color(d), lineWidth: 2))
                                .overlay(Text(String(d.name.prefix(1)).uppercased()).bold())
                            Text(d.name).font(.system(size: 10)).lineLimit(1).frame(width: 64)
                            if d.distanceM >= 0 {
                                Text(distanceLabel(d.distanceM)).font(.system(size: 9)).foregroundStyle(.secondary)
                            }
                        }
                        .position(x: x, y: y + 10)
                        .onTapGesture { selected = d }
                        .accessibilityLabel(d.name)
                    }
                }
                .frame(width: side, height: side)
                .frame(maxWidth: .infinity)
            }
            .aspectRatio(1, contentMode: .fit)
            .frame(maxWidth: 360)
            HStack(spacing: 16) {
                legend(Self.good, "Linked \(dots.filter { $0.ring == RadarRing.linked }.count)")
                legend(Self.accent, "Via relay \(dots.filter { $0.ring == RadarRing.relayed }.count)")
                legend(.gray, "Pairing \(dots.filter { $0.ring == RadarRing.nearby }.count)")
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .confirmationDialog(selected?.name ?? "", isPresented: Binding(get: { selected != nil }, set: { if !$0 { selected = nil } }), titleVisibility: .visible) {
            if let d = selected, !d.peerId.isEmpty {
                Button("Call") { model.call(d.peerId) }
            }
        } message: {
            if let d = selected {
                Text(d.peerId.isEmpty ? "Waiting for both phones to confirm the pairing code." : (d.ring == RadarRing.linked ? "Linked directly." : "Reached through another phone (relay)."))
            }
        }
    }

    private func distanceLabel(_ m: Double) -> String {
        m < 1000 ? "\(Int((m / 5).rounded()) * 5) m" : String(format: "%.1f km", m / 1000)
    }

    private func legend(_ c: Color, _ text: String) -> some View {
        HStack(spacing: 6) {
            Circle().fill(c).frame(width: 9, height: 9)
            Text(text)
        }
    }
}
