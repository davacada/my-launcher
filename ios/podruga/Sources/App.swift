import SwiftUI

enum Route: Hashable {
    case thing(UUID)
    case capture(String, Date?)
    case bestway(UUID)
    case getthere(UUID)
    case settings
    case calendars
    case notifications
}

@MainActor
final class Router: ObservableObject {
    static let shared = Router()
    @Published var tab = 0
    @Published var paths: [[Route]] = [[], [], [], []]
    @Published var voice = false

    func push(_ r: Route) { paths[tab].append(r) }
    func open(_ id: UUID) { tab = 1; paths[1] = [.thing(id)] }

    static func tabName(_ i: Int) -> String {
        [tr("Today", "Сегодня"), tr("Things", "Дела"), tr("Decide", "Решить"), tr("Calendar", "Календарь")][i]
    }

    func name(_ r: Route) -> String {
        switch r {
        case .thing: return tr("Reminder", "Назад")
        case .capture: return tr("Back", "Назад")
        case .bestway: return tr("Ways", "Способы")
        case .getthere: return tr("Route", "Маршрут")
        case .settings: return tr("Settings", "Настройки")
        case .calendars: return tr("Calendars", "Календари")
        case .notifications: return tr("Notifications", "Уведомления")
        }
    }

    func back(for r: Route, tab t: Int) -> String {
        let p = paths[t]
        if let i = p.firstIndex(of: r), i > 0 {
            if case .thing(let id) = p[i - 1], let th = Store.shared.thing(id) { return String(th.title.prefix(18)) }
            return name(p[i - 1])
        }
        return Router.tabName(t)
    }
}

@main
struct PodrugaApp: App {
    @StateObject private var store = Store.shared
    @StateObject private var cal = Cal.shared
    @StateObject private var router = Router.shared
    @AppStorage("lang") private var lang = "system"
    @Environment(\.scenePhase) private var phase

    init() {
        Demo.setupIfNeeded()
        Notifier.shared.setup()
        UITextView.appearance().backgroundColor = .clear
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(cal)
                .environmentObject(router)
                .id(lang)
                .tint(C.pink)
                .preferredColorScheme(.light)
                .onOpenURL { url in openIncoming(url) }
        }
        .onChange(of: phase) { _, p in
            if p == .active {
                cal.refreshAccess()
                Task { await Notifier.shared.reschedule() }
            }
        }
    }

    private func openIncoming(_ url: URL) {
        guard url.isFileURL, let a = store.importFile(url) else { return }
        var t = Thing(title: (url.deletingPathExtension().lastPathComponent))
        t.attachments = [a]
        store.upsert(t)
        router.open(t.id)
    }
}

struct RootView: View {
    @EnvironmentObject var router: Router
    @EnvironmentObject var cal: Cal

    var body: some View {
        ZStack(alignment: .bottom) {
            Group {
                switch router.tab {
                case 0: stack(0) { TodayView() }
                case 1: stack(1) { ThingsView() }
                case 2: stack(2) { DecideView() }
                default: stack(3) { CalendarTabView() }
                }
            }
            if router.paths[router.tab].isEmpty {
                TabBar().padding(.horizontal, 14).padding(.bottom, 24)
            }
        }
        .ignoresSafeArea(.keyboard)
        .ignoresSafeArea(edges: .bottom)
        .fullScreenCover(isPresented: $router.voice) { VoiceView() }
        .task {
            cal.refreshAccess()
            if !cal.granted { await cal.request() }
            _ = await Notifier.shared.request()
            await Notifier.shared.reschedule()
            Demo.route()
        }
    }

    @ViewBuilder
    func stack<V: View>(_ i: Int, @ViewBuilder _ root: () -> V) -> some View {
        NavigationStack(path: $router.paths[i]) {
            root()
                .navigationDestination(for: Route.self) { r in
                    destination(r, back: router.back(for: r, tab: i))
                }
        }
    }

    @ViewBuilder
    func destination(_ r: Route, back: String) -> some View {
        switch r {
        case .thing(let id): ThingView(id: id, back: back)
        case .capture(let text, let start): CaptureView(initial: text, preset: start, back: back)
        case .bestway(let id): BestWayView(id: id, back: back)
        case .getthere(let id): GetThereView(id: id, back: back)
        case .settings: SettingsView(back: back)
        case .calendars: CalendarsView(back: back)
        case .notifications: NotificationsView(back: back)
        }
    }
}

struct TabBar: View {
    @EnvironmentObject var router: Router
    let items = [("home", 0), ("list", 1), ("scale", 2), ("cal", 3)]
    var body: some View {
        HStack(spacing: 0) {
            ForEach(items, id: \.1) { icon, i in
                let on = router.tab == i
                Button { router.tab = i } label: {
                    VStack(spacing: 4) {
                        Ic(name: icon, size: 22, color: on ? C.text : C.sub)
                        Text(Router.tabName(i)).font(.inter(10.5, .semibold)).foregroundStyle(on ? C.text : C.sub)
                    }
                    .frame(maxWidth: .infinity)
                    .contentShape(Rectangle())
                }.buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 8)
        .frame(height: 64)
        .background(
            Capsule().fill(Color.white.opacity(0.92))
                .background(Capsule().fill(.ultraThinMaterial))
                .shadow(color: C.text.opacity(0.10), radius: 15, y: 10)
                .overlay(Capsule().stroke(C.text.opacity(0.06), lineWidth: 1))
        )
    }
}
