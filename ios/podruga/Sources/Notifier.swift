import UIKit
import UserNotifications

final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()
    let center = UNUserNotificationCenter.current()

    func setup() {
        center.delegate = self
        registerCategories()
    }

    func registerCategories() {
        let dir = UNNotificationAction(identifier: "DIRECTIONS", title: tr("Directions", "Маршрут"), options: [.foreground])
        let snooze = UNNotificationAction(identifier: "SNOOZE10", title: tr("Snooze 10 min", "Через 10 мин"), options: [])
        let plan = UNNotificationAction(identifier: "PLAN", title: tr("Plan it", "Запланировать"), options: [])
        let notToday = UNNotificationAction(identifier: "NOTTODAY", title: tr("Not today", "Не сегодня"), options: [])
        let open = UNNotificationAction(identifier: "OPEN", title: tr("Do it now", "Сделать сейчас"), options: [.foreground])
        let tonight = UNNotificationAction(identifier: "TONIGHT", title: tr("Remind tonight", "Напомнить вечером"), options: [])
        let move = UNNotificationAction(identifier: "MOVE", title: tr("Move to tomorrow", "Перенести на завтра"), options: [])
        let done = UNNotificationAction(identifier: "DONE", title: tr("Done", "Готово"), options: [])
        center.setNotificationCategories([
            UNNotificationCategory(identifier: "LEAVE", actions: [dir, snooze], intentIdentifiers: []),
            UNNotificationCategory(identifier: "FREE", actions: [plan, notToday], intentIdentifiers: []),
            UNNotificationCategory(identifier: "COMING", actions: [open, tonight], intentIdentifiers: []),
            UNNotificationCategory(identifier: "REMIND", actions: [done, snooze], intentIdentifiers: []),
            UNNotificationCategory(identifier: "EVENING", actions: [move], intentIdentifiers: []),
        ])
    }

    func request() async -> Bool {
        (try? await center.requestAuthorization(options: [.alert, .sound, .badge])) ?? false
    }

    // MARK: building

    struct Item {
        var id: String
        var title: String
        var body: String
        var date: Date
        var category: String
        var info: [String: Any] = [:]
        var important = false
    }

    private var sound: UNNotificationSound? {
        switch UserDefaults.standard.string(forKey: "sound") ?? "chime" {
        case "none": return nil
        case "default": return .default
        default: return UNNotificationSound(named: UNNotificationSoundName("chime.wav"))
        }
    }

    /// Move times inside quiet hours to the end of them.
    private func quiet(_ d: Date, important: Bool) -> Date {
        guard Pref.bool("quietOn", true) else { return d }
        if important && Pref.bool("breakFocus", true) { return d }
        let from = Pref.int("quietFrom", 23 * 60), to = Pref.int("quietTo", 7 * 60 + 30)
        let cal = Calendar.current
        let m = cal.component(.hour, from: d) * 60 + cal.component(.minute, from: d)
        let inside = from > to ? (m >= from || m < to) : (m >= from && m < to)
        if !inside { return d }
        var day = cal.startOfDay(for: d)
        if from > to && m >= from { day = cal.date(byAdding: .day, value: 1, to: day)! }
        return day.addingTimeInterval(Double(to) * 60)
    }

    @MainActor
    func reschedule() async {
        let store = Store.shared, cal = Cal.shared
        if store.demo { return }
        let settings = await center.notificationSettings()
        guard settings.authorizationStatus == .authorized || settings.authorizationStatus == .provisional else { return }
        registerCategories()
        let now = Date()
        var items: [Item] = []
        let offsets = Pref.remindOffsets

        for t in store.things where !t.done {
            if let s = t.start {
                for o in (t.remind ?? offsets) {
                    let at = s.addingTimeInterval(-Double(o) * 60)
                    guard at > now else { continue }
                    var body = o == 0 ? tr("Now", "Сейчас") : Fmt.when(s)
                    if let p = t.place { body += " · " + p }
                    items.append(Item(id: "r-\(t.id)-\(o)", title: t.title, body: body, date: at, category: "REMIND",
                                      info: ["id": t.id.uuidString], important: o <= 60))
                }
                if Pref.bool("leaveOn", true), let m = t.travelMinutes, m > 0 {
                    let at = s.addingTimeInterval(-Double(m + 5) * 60)
                    if at > now {
                        var body = "\(t.title) " + tr("at", "в") + " \(Fmt.time(s))"
                        if let n = t.travelNote { body += " · " + n }
                        var info: [String: Any] = ["id": t.id.uuidString]
                        if let la = t.lat, let lo = t.lon { info["lat"] = la; info["lon"] = lo }
                        if let p = t.place { info["place"] = p }
                        items.append(Item(id: "leave-\(t.id)", title: tr("Time to leave", "Пора выходить"), body: body, date: at,
                                          category: "LEAVE", info: info, important: true))
                    }
                }
            } else if let d = t.deadline, Pref.bool("deadlineOn", true) {
                let day = Calendar.current.date(byAdding: .day, value: -1, to: Calendar.current.startOfDay(for: d))!
                let at = day.addingTimeInterval(9 * 3600)
                if at > now {
                    items.append(Item(id: "due-\(t.id)", title: tr("Coming up", "Скоро срок"),
                                      body: tr("\(t.title) is due \(Fmt.shortDay(d))", "\(t.title) — до \(Fmt.shortDay(d))"),
                                      date: at, category: "COMING", info: ["id": t.id.uuidString]))
                }
            }
        }

        // free-time ideas for today
        if Pref.bool("freeOn", true) {
            let open = store.things.filter { !$0.done && $0.start == nil }
                .sorted { ($0.deadline ?? .distantFuture) < ($1.deadline ?? .distantFuture) }
            for (s, e) in cal.freeToday() where s > now.addingTimeInterval(5 * 60) {
                let mins = Int(e.timeIntervalSince(s) / 60)
                if let t = open.first(where: { $0.minutes <= mins }) {
                    items.append(Item(id: "free-\(t.id)", title: tr("Free time", "Свободное время"),
                                      body: tr("You're free until \(Fmt.time(e)). \(t.title) now? It takes about \(Fmt.dur(t.minutes)).",
                                               "Ты свободна до \(Fmt.time(e)). \(t.title) сейчас? Займёт около \(Fmt.dur(t.minutes))."),
                                      date: s, category: "FREE", info: ["id": t.id.uuidString, "start": s.timeIntervalSince1970]))
                    break
                }
            }
        }

        // morning plan and evening check, a week ahead
        let c = Calendar.current
        for i in 0..<7 {
            let day = c.date(byAdding: .day, value: i, to: c.startOfDay(for: now))!
            if Pref.bool("morningOn", true) {
                let at = day.addingTimeInterval(Double(Pref.int("morningAt", 8 * 60)) * 60)
                if at > now {
                    let ts = store.things(on: day).filter { !$0.done }
                    let evs = cal.events(on: day)
                    let all: [(Date, String)] = (ts.map { ($0.start!, $0.title) } + evs.map { ($0.start, $0.title) }).sorted { $0.0 < $1.0 }
                    var body = tr("\(all.count) things today", "Дел сегодня: \(all.count)")
                    if let f = all.first { body += tr(" · first: \(f.1) at \(Fmt.time(f.0))", " · первое: \(f.1) в \(Fmt.time(f.0))") }
                    if all.isEmpty { body = tr("Nothing planned. Write a thing?", "Ничего не запланировано. Запишем дело?") }
                    items.append(Item(id: "morning-\(i)", title: tr("Good morning ✨", "Доброе утро ✨"), body: body, date: at, category: ""))
                }
            }
            if Pref.bool("eveningOn", true) {
                let at = day.addingTimeInterval(Double(Pref.int("eveningAt", 21 * 60)) * 60)
                if at > now {
                    let left = store.things(on: day).filter { !$0.done }.count
                    if i > 0 || left > 0 {
                        items.append(Item(id: "evening-\(i)", title: tr("Evening check", "Вечерняя проверка"),
                                          body: tr("Anything left from today? Move it to tomorrow.", "Что-то осталось? Перенеси на завтра."),
                                          date: at, category: "EVENING"))
                    }
                }
            }
        }

        center.removeAllPendingNotificationRequests()
        let sorted = items.map { var x = $0; x.date = quiet(x.date, important: x.important); return x }
            .filter { $0.date > now }.sorted { $0.date < $1.date }.prefix(60)
        for it in sorted { add(it) }
    }

    func add(_ it: Item) {
        let content = UNMutableNotificationContent()
        content.title = it.title
        content.body = it.body
        content.categoryIdentifier = it.category
        content.userInfo = it.info
        content.sound = sound
        content.threadIdentifier = "podruga"
        if it.important && Pref.bool("breakFocus", true) { content.interruptionLevel = .timeSensitive }
        let comps = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute, .second], from: it.date)
        let req = UNNotificationRequest(identifier: it.id, content: content,
                                        trigger: UNCalendarNotificationTrigger(dateMatching: comps, repeats: false))
        center.add(req)
    }

    // MARK: delegate

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound, .list]
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let n = response.notification.request.content
        let info = n.userInfo
        let id = (info["id"] as? String).flatMap(UUID.init(uuidString:))
        switch response.actionIdentifier {
        case "SNOOZE10":
            add(Item(id: response.notification.request.identifier + "-s", title: n.title, body: n.body,
                     date: Date().addingTimeInterval(600), category: n.categoryIdentifier, info: info as? [String: Any] ?? [:]))
        case "TONIGHT":
            var d = Calendar.current.date(bySettingHour: 20, minute: 0, second: 0, of: Date())!
            if d < Date() { d = Date().addingTimeInterval(3600) }
            add(Item(id: response.notification.request.identifier + "-t", title: n.title, body: n.body, date: d,
                     category: n.categoryIdentifier, info: info as? [String: Any] ?? [:]))
        case "DIRECTIONS":
            if let la = info["lat"] as? Double, let lo = info["lon"] as? Double,
               let u = URL(string: "http://maps.apple.com/?daddr=\(la),\(lo)&dirflg=r") {
                await MainActor.run { UIApplication.shared.open(u) }
            }
        case "PLAN":
            if let id, let ts = info["start"] as? Double {
                await MainActor.run {
                    if var t = Store.shared.thing(id) {
                        t.start = Date(timeIntervalSince1970: ts)
                        t.eventID = Cal.shared.save(t)
                        Store.shared.upsert(t)
                    }
                }
                await reschedule()
            }
        case "DONE":
            if let id {
                await MainActor.run {
                    if var t = Store.shared.thing(id) { t.done = true; Store.shared.upsert(t) }
                }
                await reschedule()
            }
        case "MOVE":
            await MainActor.run {
                let s = Store.shared
                for var t in s.things(on: Date()) where !t.done {
                    t.start = t.start?.addingTimeInterval(86400)
                    t.eventID = Cal.shared.save(t)
                    s.upsert(t)
                }
            }
            await reschedule()
        default:
            if let id { await MainActor.run { Router.shared.open(id) } }
        }
    }
}
