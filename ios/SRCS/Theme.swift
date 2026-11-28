import SwiftUI

/// Colours and building blocks of the 0.5 look (same as Android): dark background, red glow,
/// glass panels, one glossy main button per screen.
enum Ui {
    static let bg = Color(red: 0.03, green: 0.04, blue: 0.06)
    static let red = Color(red: 1.0, green: 0.23, blue: 0.28)
    static let redLight = Color(red: 1.0, green: 0.42, blue: 0.37)
    static let purple = Color(red: 0.77, green: 0.63, blue: 1.0)
    static let purpleDeep = Color(red: 0.16, green: 0.07, blue: 0.25)
    static let pink = Color(red: 0.93, green: 0.28, blue: 0.60)
    static let cyan = Color(red: 0.13, green: 0.78, blue: 0.90)
    static let teal = Color(red: 0.08, green: 0.72, blue: 0.65)
    static let good = Color(red: 0.24, green: 0.86, blue: 0.59)
    static let warn = Color(red: 0.96, green: 0.73, blue: 0.26)
    static let amber = Color(red: 0.98, green: 0.75, blue: 0.14)
    static let text = Color(red: 0.96, green: 0.97, blue: 0.98)
    static let sub = Color(red: 0.60, green: 0.65, blue: 0.70)

    static let avatarPalette: [Color] = [
        Color(red: 0.56, green: 0.42, blue: 0.94), Color(red: 0.96, green: 0.62, blue: 0.04),
        Color(red: 0.13, green: 0.78, blue: 0.90), Color(red: 0.93, green: 0.28, blue: 0.60),
        Color(red: 0.24, green: 0.86, blue: 0.59), Color(red: 0.38, green: 0.65, blue: 0.98),
        Color(red: 0.98, green: 0.45, blue: 0.09), Color(red: 0.64, green: 0.90, blue: 0.21),
    ]

    /// Stable colour per name (same idea as Android; exact colours may differ).
    static func avatarColor(_ name: String) -> Color {
        let key = name.trimmingCharacters(in: .whitespaces).lowercased()
        var h: UInt32 = 5381
        for b in key.utf8 { h = (h &* 33) &+ UInt32(b) }
        return avatarPalette[Int(h % UInt32(avatarPalette.count))]
    }
}

extension View {
    /// Translucent panel with a thin light border.
    func glass(_ radius: CGFloat = 24, fill: Color = Color.white.opacity(0.07), line: Color = Color.white.opacity(0.12)) -> some View {
        background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(fill))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(line, lineWidth: 1))
    }
}

/// The big glossy button of a screen.
struct PrimaryPill: View {
    let title: String
    var icon: String? = nil
    var colors: [Color] = [Ui.red, Ui.redLight]
    var height: CGFloat = 58
    var textColor: Color = .white

    var body: some View {
        HStack(spacing: 10) {
            if let icon { Image(systemName: icon).font(.system(size: 20, weight: .semibold)) }
            Text(title).font(.system(size: height >= 56 ? 20 : 16, weight: .bold)).lineLimit(1)
        }
        .foregroundStyle(textColor)
        .frame(maxWidth: .infinity)
        .frame(height: height)
        .background(Capsule().fill(LinearGradient(colors: colors, startPoint: .leading, endPoint: .trailing)))
        .shadow(color: colors[0].opacity(0.45), radius: 14, y: 8)
    }
}

/// Secondary glass button label.
struct GlassLabel: View {
    let title: String
    var color: Color = Ui.text

    var body: some View {
        Text(title).font(.system(size: 14, weight: .semibold)).foregroundStyle(color)
            .frame(maxWidth: .infinity).frame(height: 44)
            .glass(22, fill: Color(red: 0.06, green: 0.09, blue: 0.12).opacity(0.85))
    }
}

/// Round avatar with the person's initial on a colour gradient.
struct AvatarBubble: View {
    let name: String
    var size: CGFloat = 48
    var ring: Color? = nil

    var body: some View {
        let c = Ui.avatarColor(name)
        Circle()
            .fill(LinearGradient(colors: [c, c.opacity(0.65)], startPoint: .topLeading, endPoint: .bottomTrailing))
            .frame(width: size, height: size)
            .overlay(Text(String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                .font(.system(size: size / 2.3, weight: .bold)).foregroundStyle(.white))
            .overlay(Circle().stroke(ring ?? Color.white.opacity(0.85), lineWidth: ring == nil ? 2 : 3))
    }
}

/// Coloured dot + short text in a glass capsule.
struct StatusChip: View {
    let text: String
    let dot: Color

    var body: some View {
        HStack(spacing: 8) {
            Circle().fill(dot).frame(width: 8, height: 8).shadow(color: dot, radius: 5)
            Text(text).font(.system(size: 13, weight: .medium)).foregroundStyle(Ui.text).lineLimit(1)
        }
        .padding(.horizontal, 12).padding(.vertical, 8)
        .glass(20)
    }
}

/// Dark background with a faint grid (the offline map is on the Map tab).
struct GridBackdrop: View {
    var body: some View {
        Canvas { g, size in
            g.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(
                Gradient(colors: [Color(red: 0.04, green: 0.09, blue: 0.13), Ui.bg]), startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height)))
            var p = Path()
            stride(from: 0, through: size.width, by: 48).forEach { x in p.move(to: CGPoint(x: x, y: 0)); p.addLine(to: CGPoint(x: x, y: size.height)) }
            stride(from: 0, through: size.height, by: 48).forEach { y in p.move(to: CGPoint(x: 0, y: y)); p.addLine(to: CGPoint(x: size.width, y: y)) }
            g.stroke(p, with: .color(Ui.cyan.opacity(0.06)), lineWidth: 1)
        }
        .ignoresSafeArea()
    }
}
