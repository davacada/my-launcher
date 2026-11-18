import EventKit
import SwiftUI

struct Ev: Identifiable, Hashable {
    let id: String
    let title: String
    let start: Date
    let end: Date
    let calendar: String
}

@MainActor
final class Cal: ObservableObject {
    static let shared = Cal()
    let store = EKEventStore()
    @Published var granted = false
    @Published var version = 0
    var demo = false
    var demoEvents: [Ev] = []
    var demoNow: Date?

    var now: Date { demoNow ?? Date() }

    init() {
        NotificationCenter.default.addObserver(forName: .EKEventStoreChanged, object: store, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.version += 1 }
        }
    }

    func refreshAccess() {
        if demo { granted = true; return }
        granted = EKEventStore.authorizationStatus(for: .event) == .fullAccess
    }

    func request() async {
        if demo { granted = true; return }
        do { granted = try await store.requestFullAccessToEvents() } catch { granted = false }
        version += 1
    }

    // MARK: which calendars count

    var off: Set<String> {
        get { Set((UserDefaults.standard.array(forKey: "calOff") as? [String]) ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: "calOff"); version += 1 }
    }

    var calendars: [EKCalendar] { granted && !demo ? store.calendars(for: .event) : [] }

    struct Account: Identifiable {
        let id: String
        let title: String
        let calendars: [EKCalendar]
        var color: Color { calendars.first.map { Color(cgColor: $0.cgColor) } ?? C.pink }
    }

    var accounts: [Account] {
        let groups = Dictionary(grouping: calendars) { $0.source?.sourceIdentifier ?? "local" }
        return groups.map { k, v in
            let src = v.first?.source
            var title = src?.title ?? "iPhone"
            if src?.sourceType == .local { title = "iPhone" }
            if src?.sourceType == .calDAV && title.lowercased().contains("icloud") { title = "iCloud" }
            return Account(id: k, title: title, calendars: v.sorted { $0.title < $1.title })
        }.sorted { $0.title < $1.title }
    }

    func isOn(_ a: Account) -> Bool { a.calendars.contains { !off.contains($0.calendarIdentifier) } }
    func set(_ a: Account, on: Bool) {
        var o = off
        for c in a.calendars { if on { o.remove(c.calendarIdentifier) } else { o.insert(c.calendarIdentifier) } }
        off = o
    }

    var writable: [EKCalendar] { calendars.filter { $0.allowsContentModifications } }

    var writeCalendar: EKCalendar? {
        if let id = UserDefaults.standard.string(forKey: "calWrite"), let c = store.calendar(withIdentifier: id) { return c }
        return store.defaultCalendarForNewEvents
    }
    func setWrite(_ c: EKCalendar) { UserDefaults.standard.set(c.calendarIdentifier, forKey: "calWrite"); version += 1 }

    // MARK: reading

    func events(from a: Date, to b: Date) -> [Ev] {
        if demo { return demoEvents.filter { $0.end > a && $0.start < b }.sorted { $0.start < $1.start } }
        guard granted else { return [] }
        let on = calendars.filter { !off.contains($0.calendarIdentifier) }
        if on.isEmpty { return [] }
        let mine = Set(Store.shared.things.compactMap { $0.eventID })
        let p = store.predicateForEvents(withStart: a, end: b, calendars: on)
        return store.events(matching: p)
            .filter { !mine.contains($0.eventIdentifier ?? "") && !$0.isAllDay }
            .map { Ev(id: $0.eventIdentifier ?? UUID().uuidString, title: $0.title ?? "", start: $0.startDate, end: $0.endDate,
                      calendar: $0.calendar.title) }
            .sorted { $0.start < $1.start }
    }

    func events(on day: Date) -> [Ev] {
        let s = Calendar.current.startOfDay(for: day)
        return events(from: s, to: s.addingTimeInterval(86400))
    }

    /// Busy spans: calendar events, planned things (with travel), and sleep.
    func busy(from a: Date, to b: Date, except: UUID? = nil) -> [(Date, Date)] {
        var spans = events(from: a, to: b).map { ($0.start, $0.end) }
        for t in Store.shared.things where !t.done && t.id != except {
            guard let s = t.start, let e = t.end else { continue }
            let travel = Pref.travelBusy ? Double(t.travelMinutes ?? 0) * 60 : 0
            spans.append((s.addingTimeInterval(-travel), e.addingTimeInterval(travel)))
        }
        if Pref.sleepBusy {
            let cal = Calendar.current
            var day = cal.startOfDay(for: a).addingTimeInterval(-86400)
            while day < b {
                let from = day.addingTimeInterval(Double(Pref.sleepFrom) * 60)
                var to = day.addingTimeInterval(Double(Pref.sleepTo) * 60)
                if to <= from { to = to.addingTimeInterval(86400) }
                spans.append((from, to))
                day = day.addingTimeInterval(86400)
            }
        }
        return spans.sorted { $0.0 < $1.0 }
    }

    /// Free gaps of at least `min` minutes between `a` and `b`.
    func free(from a: Date, to b: Date, min: Int = 45, except: UUID? = nil) -> [(Date, Date)] {
        var out: [(Date, Date)] = []
        var cur = a
        for (s, e) in busy(from: a, to: b, except: except) {
            if e <= cur { continue }
            if s > cur, s.timeIntervalSince(cur) >= Double(min) * 60 { out.append((cur, Swift.min(s, b))) }
            cur = Swift.max(cur, e)
            if cur >= b { break }
        }
        if b.timeIntervalSince(cur) >= Double(min) * 60 { out.append((cur, b)) }
        return out.filter { $0.1 > $0.0 }
    }

    /// Free time left today (for the header).
    func freeToday() -> [(Date, Date)] {
        let n = now
        let end = Calendar.current.startOfDay(for: n).addingTimeInterval(Double(Swift.min(Pref.sleepFrom, 23 * 60 + 59)) * 60)
        if end <= n { return [] }
        return free(from: n, to: end)
    }

    /// Best start for something of `minutes` before `deadline`.
    func bestSlot(minutes: Int, travel: Int = 0, deadline: Date?, except: UUID? = nil) -> Date? {
        let cal = Calendar.current
        let n = now
        var startFrom = n.addingTimeInterval(15 * 60)
        let minuteRound = cal.component(.minute, from: startFrom) % 15
        if minuteRound != 0 { startFrom = startFrom.addingTimeInterval(Double(15 - minuteRound) * 60) }
        let limit = deadline ?? n.addingTimeInterval(14 * 86400)
        let need = minutes + 2 * travel
        var day = cal.startOfDay(for: startFrom)
        while day < limit {
            let open = Swift.max(day.addingTimeInterval(9 * 3600), startFrom)
            let close = Swift.min(day.addingTimeInterval(21 * 3600), limit)
            if close > open {
                for (s, e) in free(from: open, to: close, min: need, except: except) {
                    if e.timeIntervalSince(s) >= Double(need) * 60 {
                        var st = s.addingTimeInterval(Double(travel) * 60)
                        let r = cal.component(.minute, from: st) % 15
                        if r != 0 { st = st.addingTimeInterval(Double(15 - r) * 60) }
                        if st.addingTimeInterval(Double(minutes + travel) * 60) <= e { return st }
                    }
                }
            }
            day = day.addingTimeInterval(86400)
        }
        return nil
    }

    /// Why this time: the event just before it.
    func reason(for start: Date) -> String {
        let before = events(from: Calendar.current.startOfDay(for: start), to: start).last
        if let b = before, start.timeIntervalSince(b.end) < 3 * 3600 {
            return tr("You're free after \(b.title)", "Ты свободна после: \(b.title)")
        }
        return tr("Nothing else is planned then", "В это время ничего не запланировано")
    }

    // MARK: writing

    func save(_ t: Thing) -> String? {
        if demo { return nil }
        guard granted, let s = t.start, let e = t.end else { return t.eventID }
        let ev: EKEvent
        if let id = t.eventID, let old = store.event(withIdentifier: id) { ev = old } else {
            ev = EKEvent(eventStore: store)
            guard let c = writeCalendar else { return nil }
            ev.calendar = c
        }
        ev.title = t.title
        ev.startDate = s
        ev.endDate = e
        ev.location = t.place
        ev.notes = [t.firstStep.map { tr("First step: ", "Первый шаг: ") + $0 }, t.note, "— \(appName)"].compactMap { $0 }.joined(separator: "\n")
        do { try store.save(ev, span: .thisEvent); return ev.eventIdentifier } catch { return t.eventID }
    }

    func remove(eventID: String) {
        if demo { return }
        if let ev = store.event(withIdentifier: eventID) { try? store.remove(ev, span: .thisEvent) }
    }
}
