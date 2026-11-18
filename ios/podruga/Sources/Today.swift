import SwiftUI

@MainActor
enum Planner {
    /// Turn what she wrote into a thing.
    static func make(text: String, parsed p: Parsed, ai: AIThing?, start: Date?) -> Thing {
        var t = Thing(title: p.title)
        t.raw = text
        if let ai, !ai.title.isEmpty { t.title = ai.title }
        t.minutes = p.minutes ?? ai?.minutes ?? LocalBrain.minutes(text) ?? 30
        t.deadline = p.deadline
        t.start = start
        t.place = p.place ?? ((ai?.place.isEmpty == false) ? ai?.place : nil)
        if let s = ai?.firstStep, !s.isEmpty { t.firstStep = s } else { t.firstStep = LocalBrain.firstStep(text) }
        if let tip = ai?.tip, !tip.isEmpty { t.note = tip }
        for l in p.links {
            t.attachments.append(Attachment(kind: .link, url: l.absoluteString, title: l.host ?? l.absoluteString))
        }
        return t
    }

    /// Save it everywhere: list, calendar, travel time, notifications.
    static func commit(_ thing: Thing) async {
        var t = thing
        Store.shared.upsert(t)
        if let place = t.place, t.start != nil, !Store.shared.demo {
            var item = await Travel.shared.resolve(place)
            if item == nil, let la = t.lat, let lo = t.lon {
                item = .init(placemark: .init(coordinate: .init(latitude: la, longitude: lo)))
            }
            if let item {
                t.lat = item.placemark.coordinate.latitude
                t.lon = item.placemark.coordinate.longitude
                if let full = item.placemark.title, t.place?.count ?? 0 < 6 { t.place = full }
                let ways = await Travel.shared.ways(to: item, arrive: t.start, stayMinutes: t.minutes)
                if let b = ways.first(where: { $0.best }) {
                    t.travelMinutes = b.minutes
                    t.travelNote = b.name + (b.note.isEmpty ? "" : " · " + b.note)
                }
            }
        }
        t.eventID = Cal.shared.save(t)
        Store.shared.upsert(t)
        await Notifier.shared.reschedule()
    }
}

struct TodayView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var cal: Cal
    @EnvironmentObject var router: Router

    var now: Date { cal.now }

    var greeting: String {
        let h = Calendar.current.component(.hour, from: now)
        if h < 12 { return tr("Good morning", "Доброе утро") }
        if h < 18 { return tr("Good afternoon", "Добрый день") }
        return tr("Good evening", "Добрый вечер")
    }

    var summary: String {
        let n = store.things(on: now).filter { !$0.done }.count + cal.events(on: now).count
        let free = cal.freeToday().reduce(0) { $0 + Int($1.1.timeIntervalSince($1.0) / 60) }
        let evening = Calendar.current.component(.hour, from: now) >= 17
        var s = tr("\(n) things today", "\(n) " + ruThings(n) + " сегодня")
        if free >= 30 {
            s += " · " + (evening ? tr("\(Fmt.dur(free)) free this evening", "вечером \(Fmt.dur(free)) свободно")
                                  : tr("\(Fmt.dur(free)) free today", "сегодня \(Fmt.dur(free)) свободно"))
        }
        return s
    }

    var body: some View {
        ZStack(alignment: .bottomTrailing) {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(Fmt.dayHeader(now)).font(.inter(13, .semibold)).foregroundStyle(C.sub)
                            Text(greeting + " ✨").font(.inter(30, .heavy)).tracking(-0.6).foregroundStyle(C.text)
                                .padding(.top, 4).padding(.bottom, 2)
                            Text(summary).font(.inter(14)).foregroundStyle(C.sub)
                        }
                        Spacer()
                        GearButton()
                    }
                    .padding(.bottom, 16)

                    Button { router.push(.capture("", nil)) } label: {
                        HStack(spacing: 10) {
                            ZStack {
                                Circle().fill(C.grad).frame(width: 30, height: 30)
                                Ic(name: "plus", size: 18)
                            }
                            Text(tr("Write a thing…", "Напиши дело…")).font(.inter(15.5)).foregroundStyle(C.sub)
                            Spacer()
                        }
                        .padding(.horizontal, 16).padding(.vertical, 14)
                        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(C.tint))
                        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).stroke(C.line, lineWidth: 1))
                    }.buttonStyle(.plain)
                    .padding(.bottom, 18)

                    if !cal.granted { ConnectCalendarCard().padding(.bottom, 12) }
                    TimelineList(day: now)
                }
                .padding(.horizontal, 22)
                .padding(.top, 8)
                .padding(.bottom, 190)
            }

            Button { router.voice = true } label: {
                ZStack {
                    Circle().fill(C.grad).frame(width: 66, height: 66)
                        .shadow(color: C.pink.opacity(0.35), radius: 12, y: 10)
                    Ic(name: "mic", size: 28)
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(tr("Record a thing", "Записать голосом"))
            .padding(.trailing, 22).padding(.bottom, 104)
            .ignoresSafeArea(edges: .bottom)
        }
        .screen("Подруга: today")
    }
}

func ruThings(_ n: Int) -> String {
    let m10 = n % 10, m100 = n % 100
    if m10 == 1 && m100 != 11 { return "дело" }
    if (2...4).contains(m10) && !(12...14).contains(m100) { return "дела" }
    return "дел"
}

struct GearButton: View {
    @EnvironmentObject var router: Router
    var body: some View {
        Button { router.push(.settings) } label: {
            Ic(name: "gear", size: 20, color: C.sub)
                .frame(width: 38, height: 38)
                .background(Circle().fill(Color.white.opacity(0.8)))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tr("Settings", "Настройки"))
    }
}

struct ConnectCalendarCard: View {
    @EnvironmentObject var cal: Cal
    var body: some View {
        Button { Task { await cal.request() } } label: {
            HStack(spacing: 12) {
                Ic(name: "cal", size: 22, color: C.pink)
                VStack(alignment: .leading, spacing: 2) {
                    Text(tr("Connect your calendars", "Подключи календари")).font(.inter(15, .semibold)).foregroundStyle(C.text)
                    Text(tr("So \(appName) can find your free time", "Чтобы \(appName) видела свободное время")).font(.inter(12)).foregroundStyle(C.sub)
                }
                Spacer()
                Ic(name: "chev", size: 16, color: C.sub)
            }
            .padding(14)
            .card(18)
        }.buttonStyle(.plain)
    }
}

// MARK: - Timeline

enum Block: Identifiable {
    case event(Ev)
    case thing(Thing)
    case free(Date, Date)
    var id: String {
        switch self {
        case .event(let e): return "e" + e.id
        case .thing(let t): return "t" + t.id.uuidString
        case .free(let a, _): return "f\(a.timeIntervalSince1970)"
        }
    }
    var start: Date {
        switch self {
        case .event(let e): return e.start
        case .thing(let t): return t.start ?? .distantPast
        case .free(let a, _): return a
        }
    }
}

struct TimelineList: View {
    let day: Date
    @EnvironmentObject var store: Store
    @EnvironmentObject var cal: Cal
    @EnvironmentObject var router: Router

    var blocks: [Block] {
        let c = Calendar.current
        let start = c.startOfDay(for: day)
        var out: [Block] = cal.events(on: day).map { .event($0) } + store.things(on: day).map { .thing($0) }
        var from = max(start.addingTimeInterval(8 * 3600), cal.now)
        let m = c.component(.minute, from: from) % 15
        if m != 0 { from = from.addingTimeInterval(Double(15 - m) * 60) }
        let to = start.addingTimeInterval(Double(min(Pref.sleepFrom, 22 * 60 + 30)) * 60)
        if to > from {
            for (a, b) in cal.free(from: from, to: to, min: 45) { out.append(.free(a, b)) }
        }
        return out.sorted { $0.start < $1.start }
    }

    var body: some View {
        let list = blocks
        VStack(spacing: 10) {
            if list.isEmpty {
                Text(tr("Nothing planned. Write a thing and \(appName) will find time for it.",
                        "Пока ничего нет. Напиши дело — \(appName) найдёт для него время."))
                    .font(.inter(14)).foregroundStyle(C.sub).frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16).card(18)
            }
            ForEach(list) { b in row(b) }
        }
        .id(cal.version)
    }

    @ViewBuilder
    func row(_ b: Block) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Text(Fmt.time(b.start)).font(.inter(12)).foregroundStyle(C.sub)
                .frame(width: 44, alignment: .trailing).padding(.top, 12)
            switch b {
            case .event(let e):
                BlockCard(title: e.title, sub: e.calendar, kind: 0)
            case .thing(let t):
                Button { router.push(.thing(t.id)) } label: {
                    BlockCard(title: t.title, sub: "\(appName): " + thingSub(t), kind: 1, done: t.done)
                }.buttonStyle(.plain)
            case .free(let a, let z):
                Button { router.push(.capture("", a)) } label: {
                    BlockCard(title: tr("Free", "Свободно") + " · " + Fmt.dur(Int(z.timeIntervalSince(a) / 60)),
                              sub: tr("\(appName) can put something here", "\(appName) может что-то сюда поставить"), kind: 2)
                }.buttonStyle(.plain)
            }
        }
    }

    func thingSub(_ t: Thing) -> String {
        if let n = t.note, !n.isEmpty { return n }
        var parts = [Fmt.dur(t.minutes)]
        if let p = t.place { parts.append(p) }
        return parts.joined(separator: " · ")
    }
}

struct BlockCard: View {
    let title: String
    let sub: String
    let kind: Int   // 0 calendar, 1 Подруга, 2 free
    var done = false

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.inter(15, .semibold)).foregroundStyle(kind == 2 ? C.sub : C.text)
                .strikethrough(done, color: C.text.opacity(0.5))
            Text(sub).font(.inter(12)).foregroundStyle(kind == 1 ? C.text.opacity(0.7) : C.sub).lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14).padding(.vertical, 12)
        .background {
            let r = RoundedRectangle(cornerRadius: 18, style: .continuous)
            switch kind {
            case 0: r.fill(C.mint)
            case 1: r.fill(C.grad)
            default: r.strokeBorder(C.pink.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [4, 3]))
            }
        }
        .opacity(done ? 0.55 : 1)
    }
}

// MARK: - Calendar tab

struct CalendarTabView: View {
    @EnvironmentObject var cal: Cal
    @State private var selected = Calendar.current.startOfDay(for: Date())

    var days: [Date] {
        let s = Calendar.current.startOfDay(for: cal.now)
        return (0..<21).map { s.addingTimeInterval(Double($0) * 86400 + 3600) }.map { Calendar.current.startOfDay(for: $0) }
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(tr("CALENDAR", "КАЛЕНДАРЬ")).font(.inter(13, .semibold)).foregroundStyle(C.sub)
                        Text(Fmt.monthTitle(selected)).font(.inter(30, .heavy)).tracking(-0.6).foregroundStyle(C.text).padding(.top, 4)
                    }
                    Spacer()
                    GearButton()
                }
                .padding(.horizontal, 22)

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(days, id: \.self) { d in
                            let on = Calendar.current.isDate(d, inSameDayAs: selected)
                            Button { selected = d } label: {
                                VStack(spacing: 4) {
                                    Text(Fmt.weekdayShort(d)).font(.inter(11, .semibold))
                                    Text("\(Calendar.current.component(.day, from: d))").font(.inter(17, .bold))
                                }
                                .foregroundStyle(on ? C.text : C.sub)
                                .frame(width: 48, height: 64)
                                .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
                                    .fill(on ? AnyShapeStyle(C.grad) : AnyShapeStyle(Color.white)))
                            }.buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 22).padding(.vertical, 16)
                }

                Text(Fmt.dayHeader(selected)).font(.inter(12.5, .bold)).foregroundStyle(C.pink).tracking(0.5)
                    .padding(.horizontal, 22).padding(.bottom, 10)
                TimelineList(day: selected).padding(.horizontal, 22)
            }
            .padding(.top, 8).padding(.bottom, 120)
        }
        .onAppear { selected = Calendar.current.startOfDay(for: cal.now) }
        .screen("Подруга: calendar")
    }
}
