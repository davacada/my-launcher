import SwiftUI

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

enum C {
    static let bg = Color(hex: 0xFBF6F3)
    static let text = Color(hex: 0x3A2340)
    static let sub = Color(hex: 0x8E7A8C)
    static let pink = Color(hex: 0xE8458B)
    static let p1 = Color(hex: 0xFF8FC0)
    static let p2 = Color(hex: 0xFFB89A)
    static let mint = Color(hex: 0xC8F0D4)
    static let soft = Color(hex: 0xFFE3EF)
    static let outline = Color(hex: 0xF0609E)
    static let line = Color(hex: 0x3A2340, alpha: 0.07)
    static let tint = Color(hex: 0x3A2340, alpha: 0.06)
    static let grad = LinearGradient(colors: [p1, p2], startPoint: .topLeading, endPoint: .bottomTrailing)
}

extension Font {
    static func inter(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let name: String
        switch weight {
        case .medium: name = "Inter-Medium"
        case .semibold: name = "Inter-SemiBold"
        case .bold: name = "Inter-Bold"
        case .heavy, .black: name = "Inter-ExtraBold"
        default: name = "Inter-Regular"
        }
        return .custom(name, fixedSize: size)
    }
}

/// One of the line icons drawn for the designs.
struct Ic: View {
    let name: String
    var size: CGFloat = 22
    var color: Color = C.text
    var body: some View {
        Image("i_" + name)
            .renderingMode(.template)
            .resizable()
            .interpolation(.high)
            .frame(width: size, height: size)
            .foregroundStyle(color)
    }
}

// MARK: - Background: cream, pink and mint glow, butterflies and flowers

struct SeededRandom {
    private var state: UInt64
    init(_ seed: String) {
        var h: UInt64 = 5381
        for b in seed.utf8 { h = (h &* 33) &+ UInt64(b) }
        state = h | 1
    }
    mutating func next() -> Double {
        state = state &* 6364136223846793005 &+ 1442695040888963407
        return Double((state >> 33) % 1_000_000) / 1_000_000
    }
    mutating func int(_ a: Int, _ b: Int) -> Int { a + Int(next() * Double(b - a + 1)) }
    mutating func range(_ a: Double, _ b: Double) -> Double { a + next() * (b - a) }
}

struct Sprite: Identifiable {
    let id: Int
    let image: String
    let x: CGFloat, y: CGFloat, size: CGFloat, rotation: Double, opacity: Double
}

func sprites(_ seed: String) -> [Sprite] {
    var r = SeededRandom(seed)
    var spots: [(Int, Int)] = []
    for _ in 0..<4 { spots.append((r.int(-16, -4), r.int(220, 700))) }
    for _ in 0..<4 { spots.append((r.int(362, 378), r.int(200, 700))) }
    spots.append((r.int(40, 140), r.int(650, 730)))
    spots.append((r.int(220, 320), r.int(650, 730)))
    spots.append((r.int(300, 350), r.int(64, 90)))
    var out: [Sprite] = []
    for (i, p) in spots.enumerated() {
        if i % 2 == 0 {
            out.append(Sprite(id: i, image: "d_flower\(r.int(0, 7))", x: CGFloat(p.0), y: CGFloat(p.1),
                              size: CGFloat(r.int(26, 44)), rotation: Double(r.int(0, 70)), opacity: r.range(0.6, 0.9)))
        } else {
            out.append(Sprite(id: i, image: "d_bfly\(r.int(0, 3))", x: CGFloat(p.0), y: CGFloat(p.1),
                              size: CGFloat(r.int(30, 46)), rotation: Double(r.int(-30, 30)), opacity: r.range(0.65, 0.95)))
        }
    }
    return out
}

struct Backdrop: View {
    var seed: String
    var body: some View {
        GeometryReader { g in
            let w = g.size.width, h = g.size.height
            ZStack(alignment: .topLeading) {
                C.bg
                RadialGradient(colors: [C.p1.opacity(0.6), C.p1.opacity(0)], center: .topLeading,
                               startRadius: 0, endRadius: w * 0.7 * 0.7)
                    .scaleEffect(x: 1, y: (h * 0.4) / (w * 0.7), anchor: .topLeading)
                RadialGradient(colors: [Color(hex: 0x96E6B4, alpha: 0.55), Color(hex: 0x96E6B4, alpha: 0)],
                               center: .bottomTrailing, startRadius: 0, endRadius: w * 0.6 * 0.7)
                    .scaleEffect(x: 1, y: (h * 0.35) / (w * 0.6), anchor: .bottomTrailing)
                ForEach(sprites(seed)) { s in
                    Image(s.image)
                        .resizable()
                        .frame(width: s.size, height: s.size)
                        .rotationEffect(.degrees(s.rotation))
                        .opacity(s.opacity)
                        .position(x: s.x * w / 393 + s.size / 2, y: s.y * h / 852 + s.size / 2)
                }
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

// MARK: - Shared pieces

struct CardBG: ViewModifier {
    var radius: CGFloat = 22
    func body(content: Content) -> some View {
        content.background(
            RoundedRectangle(cornerRadius: radius, style: .continuous)
                .fill(Color.white)
                .shadow(color: C.text.opacity(0.06), radius: 8, x: 0, y: 4)
        )
    }
}

extension View {
    func card(_ radius: CGFloat = 22) -> some View { modifier(CardBG(radius: radius)) }
    func screen(_ seed: String) -> some View {
        self.background(Backdrop(seed: seed))
            .toolbar(.hidden, for: .navigationBar)
    }
}

struct SectionLabel: View {
    let text: String
    var body: some View {
        HStack(spacing: 8) {
            Ic(name: "spark", size: 15, color: C.pink)
            Text(text.uppercased()).font(.inter(12.5, .bold)).tracking(0.5).foregroundStyle(C.pink)
        }
        .padding(.top, 16).padding(.bottom, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct BackButton: View {
    let title: String
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        Button { dismiss() } label: {
            HStack(spacing: 6) {
                Ic(name: "back", size: 20, color: C.sub)
                Text(title).font(.inter(15)).foregroundStyle(C.sub)
            }
        }.buttonStyle(.plain)
    }
}

struct Header: View {
    var back: String?
    let title: String
    var sub: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let back { BackButton(title: back).padding(.bottom, 14) }
            Text(title).font(.inter(26, .heavy)).tracking(-0.3).foregroundStyle(C.text)
                .fixedSize(horizontal: false, vertical: true)
            if let sub, !sub.isEmpty {
                Text(sub).font(.inter(13.5)).foregroundStyle(C.sub).padding(.top, 4).padding(.bottom, 2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct PinkToggle: View {
    @Binding var isOn: Bool
    var body: some View {
        Button { withAnimation(.easeOut(duration: 0.18)) { isOn.toggle() } } label: {
            ZStack(alignment: isOn ? .trailing : .leading) {
                Capsule().fill(isOn ? AnyShapeStyle(C.grad) : AnyShapeStyle(C.text.opacity(0.12)))
                Circle().fill(Color.white).frame(width: 22, height: 22)
                    .shadow(color: .black.opacity(0.2), radius: 1.5, y: 1)
                    .padding(3)
            }
            .frame(width: 46, height: 28)
        }.buttonStyle(.plain)
    }
}

struct Chip: View {
    let text: String
    var on: Bool
    var body: some View {
        Text(text).font(.inter(12.5, .semibold))
            .foregroundStyle(on ? C.text : C.sub)
            .padding(.horizontal, 11).padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 13, style: .continuous)
                    .fill(on ? AnyShapeStyle(C.grad) : AnyShapeStyle(C.tint))
            )
    }
}

/// Simple wrapping layout for chips.
struct Flow: Layout {
    var spacing: CGFloat = 6
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxW = proposal.width ?? 360
        var x: CGFloat = 0, y: CGFloat = 0, rowH: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x + sz.width > maxW, x > 0 { x = 0; y += rowH + spacing; rowH = 0 }
            x += sz.width + spacing
            rowH = max(rowH, sz.height)
        }
        return CGSize(width: maxW, height: y + rowH)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, rowH: CGFloat = 0
        for s in subviews {
            let sz = s.sizeThatFits(.unspecified)
            if x + sz.width > bounds.maxX, x > bounds.minX { x = bounds.minX; y += rowH + spacing; rowH = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(sz))
            x += sz.width + spacing
            rowH = max(rowH, sz.height)
        }
    }
}

struct InfoRow: View {
    let icon: String
    let label: String
    let value: String
    var last = false
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Ic(name: icon, size: 20, color: C.pink)
                Text(label).font(.inter(13)).foregroundStyle(C.sub)
                Spacer(minLength: 8)
                Text(value).font(.inter(14.5, .semibold)).foregroundStyle(C.text).multilineTextAlignment(.trailing)
            }
            .padding(.vertical, 12)
            if !last { Rectangle().fill(C.line).frame(height: 1) }
        }
    }
}

struct PillButton: View {
    let title: String
    var dark = false
    var soft = false
    var action: () -> Void
    var body: some View {
        Button(action: action) {
            Text(title).font(.inter(16, dark ? .bold : .semibold))
                .foregroundStyle(dark ? C.bg : C.text)
                .frame(maxWidth: .infinity).frame(height: 52)
                .background(Capsule().fill(dark ? C.text : (soft ? C.tint : Color.white)))
                .shadow(color: (dark || soft) ? .clear : C.text.opacity(0.06), radius: 7, y: 4)
        }.buttonStyle(.plain)
    }
}
