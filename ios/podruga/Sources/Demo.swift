import Foundation

/// Example data for screenshots: start with `-demo -screen today -lang ru`.
@MainActor
enum Demo {
    static var on: Bool { ProcessInfo.processInfo.arguments.contains("-demo") }
    static var screen: String { UserDefaults.standard.string(forKey: "screen") ?? "today" }
    static var ids: [String: UUID] = [:]

    static func setupIfNeeded() {
        guard on else { return }
        let c = Calendar.current
        let today = c.startOfDay(for: Date())
        func at(_ d: Date, _ h: Int, _ m: Int = 0) -> Date { c.date(bySettingHour: h, minute: m, second: 0, of: d)! }
        let cal = Cal.shared, store = Store.shared
        cal.demo = true; cal.granted = true
        cal.demoNow = at(today, 18, 5)
        store.demo = true
        Travel.shared.demo = true
        let calName = tr("Calendar", "Календарь")
        cal.demoEvents = [
            Ev(id: "e1", title: tr("Lecture", "Лекция"), start: at(today, 9), end: at(today, 10, 30), calendar: calName),
            Ev(id: "e2", title: tr("Gym", "Спортзал"), start: at(today, 20), end: at(today, 21), calendar: calName),
        ]
        let thu = Parser.nextWeekday(5, from: today)
        let tue = Parser.nextWeekday(3, from: today)
        let fri = Parser.nextWeekday(6, from: today)
        var gift = Thing(title: tr("Buy Ana's birthday gift", "Купить подарок Ане"))
        gift.start = at(today, 13); gift.minutes = 15; gift.deadline = at(fri, 23, 59)
        gift.note = tr("order online, 15 min · arrives Fri", "онлайн, 15 мин · придёт в пт")
        gift.raw = tr("buy Ana's birthday gift by friday, budget about €40", "подарок Ане до пятницы, бюджет около €40")
        gift.ways = [
            WayOption(name: tr("Order online", "Заказать онлайн"), cost: tr("€32 + free delivery", "€32 + бесплатная доставка"),
                      time: tr("15 min", "15 мин"), doneBy: tr("Thu, arrives in 2 days", "чт, доставка за 2 дня"), best: true),
            WayOption(name: tr("Shop in town", "Купить в городе"), cost: "€35", time: tr("1 h 30 with travel", "1 ч 30 с дорогой"),
                      doneBy: tr("today", "сегодня"), best: false),
            WayOption(name: tr("Make something", "Сделать самой"), cost: "€10", time: tr("3 h", "3 ч"),
                      doneBy: tr("needs the weekend", "нужны выходные"), best: false),
        ]
        gift.waysWhy = tr("You're busy until Thursday and online is the cheapest that still arrives in time.",
                          "Ты занята до четверга, а онлайн дешевле всего и придёт вовремя.")
        var call = Thing(title: tr("Call the dentist", "Позвонить стоматологу"))
        call.start = at(today, 18, 30); call.minutes = 10
        call.note = tr("before 19:00 they close", "до 19:00, потом закрыто")
        var passport = Thing(title: tr("Renew passport", "Продлить паспорт"))
        passport.start = at(thu, 16); passport.minutes = 45
        passport.deadline = Parser.makeDate(day: 10, month: 1, year: nil, now: Date())
        passport.place = "Upravna enota Ljubljana"
        passport.firstStep = tr("book an appointment", "записаться на приём")
        passport.attachments = [
            Attachment(kind: .photo, title: "1.jpg"), Attachment(kind: .photo, title: "2.jpg"), Attachment(kind: .photo, title: "3.jpg"),
            Attachment(kind: .file, title: "passport_form.pdf", size: 340_000),
            Attachment(kind: .link, url: "https://e-uprava.gov.si", title: tr("Book an appointment", "Записаться на приём")),
            Attachment(kind: .voice, title: "voice", seconds: 18),
        ]
        var dentist = Thing(title: tr("Dentist", "Стоматолог"))
        dentist.start = at(tue, 15); dentist.minutes = 45
        dentist.place = "Trubarjeva 12"; dentist.lat = 46.0537; dentist.lon = 14.5086
        dentist.travelMinutes = 25
        store.things = [gift, call, passport, dentist]
        ids = ["gift": gift.id, "passport": passport.id, "dentist": dentist.id]
        store.decision = Decision(
            question: tr("Which phone plan?", "Какой тариф взять?"),
            options: ["A1 · €15", "Telemach · €12", "HoT · €9"],
            criteria: [tr("Price", "Цена"), tr("Data", "Интернет"), tr("Coverage", "Покрытие"), tr("EU roaming", "Роуминг в ЕС"), tr("Contract", "Договор")],
            chosen: [tr("Price", "Цена"), tr("Data", "Интернет"), tr("Coverage", "Покрытие")],
            pick: "Telemach · €12",
            why: tr("€3 more than HoT, but twice the data and better coverage where you live. A1 costs the most for the same.",
                    "На €3 дороже HoT, но вдвое больше интернета и лучше покрытие у тебя дома. A1 дороже всех за то же самое."),
            scores: ["Telemach · €12": 8, "HoT · €9": 7, "A1 · €15": 5])
    }

    static func route() {
        guard on else { return }
        let r = Router.shared
        switch screen {
        case "capture": r.tab = 0; r.paths[0] = [.capture(tr("renew my passport before the trip in january", "продлить паспорт до поездки в январе"), nil)]
        case "bestway": r.tab = 1; r.paths[1] = [.bestway(ids["gift"]!)]
        case "attach": r.tab = 1; r.paths[1] = [.thing(ids["passport"]!)]
        case "getthere": r.tab = 1; r.paths[1] = [.getthere(ids["dentist"]!)]
        case "decide": r.tab = 2
        case "calendar": r.tab = 3
        case "things": r.tab = 1
        case "settings": r.tab = 0; r.paths[0] = [.settings]
        case "calendars": r.tab = 0; r.paths[0] = [.settings, .calendars]
        case "notify": r.tab = 0; r.paths[0] = [.settings, .notifications]
        case "voice": r.voice = true
        default: r.tab = 0
        }
    }
}
