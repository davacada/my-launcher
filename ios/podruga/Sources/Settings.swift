import EventKit
import SwiftUI

struct SRow<Right: View>: View {
    let title: String
    var sub: String?
    var last = false
    @ViewBuilder var right: () -> Right
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.inter(15, .semibold)).foregroundStyle(C.text)
                    if let sub { Text(sub).font(.inter(12)).foregroundStyle(C.sub) }
                }
                Spacer(minLength: 8)
                right()
            }
            .padding(.vertical, 12)
            if !last { Rectangle().fill(C.line).frame(height: 1) }
        }
    }
}

struct ValueText: View {
    let text: String
    var body: some View { Text(text + " ›").font(.inter(14)).foregroundStyle(C.sub) }
}

struct SettingsView: View {
    let back: String
    @EnvironmentObject var router: Router
    @AppStorage("lang") private var lang = "system"
    @AppStorage("busFare") private var busFare = 1.30
    @AppStorage("fuelPrice") private var fuelPrice = 1.50
    @AppStorage("consumption") private var consumption = 6.5
    @AppStorage("parkingHour") private var parkingHour = 1.30

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                Header(back: back, title: tr("Settings", "Настройки"))
                SectionLabel(text: tr("Your \(appName)", "Твоя \(appName)"))
                VStack(spacing: 0) {
                    Button { router.push(.calendars) } label: {
                        SRow(title: tr("Calendars", "Календари"), sub: tr("Where your free time comes from", "Откуда брать свободное время")) { Ic(name: "chev", size: 16, color: C.sub) }
                    }.buttonStyle(.plain)
                    Button { router.push(.notifications) } label: {
                        SRow(title: tr("Notifications", "Уведомления"), sub: tr("Reminders, nudges, sound", "Напоминания, подсказки, звук")) { Ic(name: "chev", size: 16, color: C.sub) }
                    }.buttonStyle(.plain)
                    SRow(title: tr("Language", "Язык")) {
                        Menu {
                            Button(tr("Same as iPhone", "Как на iPhone")) { lang = "system" }
                            Button("English") { lang = "en" }
                            Button("Русский") { lang = "ru" }
                        } label: {
                            ValueText(text: lang == "ru" ? "Русский" : (lang == "en" ? "English" : tr("Same as iPhone", "Как на iPhone")))
                        }
                    }
                    SRow(title: tr("Apple Intelligence", "Apple Intelligence"), sub: tr("Ways to do it, help me decide", "Способы, помощь с решением"), last: true) {
                        Text(AI.status).font(.inter(13)).foregroundStyle(AI.available ? C.pink : C.sub).multilineTextAlignment(.trailing)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 4).card()

                SectionLabel(text: tr("Costs for getting there", "Цены на дорогу"))
                VStack(spacing: 0) {
                    money(tr("Bus ticket", "Билет на автобус"), $busFare, "€")
                    money(tr("Petrol per litre", "Бензин за литр"), $fuelPrice, "€")
                    money(tr("Car uses per 100 km", "Расход на 100 км"), $consumption, "l")
                    money(tr("Parking per hour", "Парковка в час"), $parkingHour, "€", last: true)
                }
                .padding(.horizontal, 16).padding(.vertical, 4).card()
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .screen("Подруга: settings")
    }

    func money(_ title: String, _ v: Binding<Double>, _ unit: String, last: Bool = false) -> some View {
        SRow(title: title, last: last) {
            HStack(spacing: 4) {
                Text(unit).font(.inter(14)).foregroundStyle(C.sub)
                TextField("0", value: v, format: .number.precision(.fractionLength(2)))
                    .font(.inter(14, .semibold)).foregroundStyle(C.text)
                    .keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(width: 60)
            }
        }
    }
}

struct CalendarsView: View {
    let back: String
    @EnvironmentObject var cal: Cal
    @AppStorage("sleepBusy") private var sleepBusy = true
    @AppStorage("travelBusy") private var travelBusy = true
    @AppStorage("sleepFrom") private var sleepFrom = 23 * 60 + 30
    @AppStorage("sleepTo") private var sleepTo = 7 * 60 + 30
    @State private var howTo = false

    struct Row: Identifiable { let id: String; let color: Color; let title: String; let sub: String }

    var rows: [Row] {
        if cal.demo {
            return [
                Row(id: "icloud", color: Color(hex: 0x3B82F6), title: "iCloud", sub: tr("Home · Personal", "Дом · Личное")),
                Row(id: "google", color: Color(hex: 0xEA4335), title: "Google", sub: "ana.k@gmail.com"),
                Row(id: "outlook", color: Color(hex: 0x0F6CBD), title: "Outlook", sub: tr("Faculty · Exchange", "Факультет · Exchange")),
            ]
        }
        return cal.accounts.map { Row(id: $0.id, color: $0.color, title: $0.title, sub: $0.calendars.map(\.title).joined(separator: " · ")) }
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                Header(back: back, title: tr("Calendars", "Календари"),
                       sub: tr("\(appName) reads every calendar on this iPhone to find your free time",
                               "\(appName) смотрит все календари на этом iPhone, чтобы найти свободное время"))
                    .padding(.bottom, 14)
                if !cal.granted {
                    PillButton(title: tr("Allow calendar access", "Разрешить доступ к календарю"), dark: true) {
                        Task {
                            await cal.request()
                            if !cal.granted, let u = URL(string: UIApplication.openSettingsURLString) { await UIApplication.shared.open(u) }
                        }
                    }
                } else {
                    VStack(spacing: 0) {
                        let list = rows
                        ForEach(Array(list.enumerated()), id: \.element.id) { i, r in
                            HStack(spacing: 12) {
                                Circle().fill(r.color).frame(width: 12, height: 12)
                                VStack(alignment: .leading, spacing: 1) {
                                    Text(r.title).font(.inter(15.5, .semibold)).foregroundStyle(C.text)
                                    Text(r.sub).font(.inter(12.5)).foregroundStyle(C.sub).lineLimit(1)
                                }
                                Spacer()
                                PinkToggle(isOn: binding(r.id))
                            }
                            .padding(.vertical, 13)
                            if i < list.count - 1 { Rectangle().fill(C.line).frame(height: 1) }
                        }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 4).card()
                }

                Button { howTo = true } label: {
                    HStack(spacing: 8) {
                        Ic(name: "plus", size: 18)
                        Text(tr("Add an account (Google, Outlook…)", "Добавить аккаунт (Google, Outlook…)")).font(.inter(15, .semibold))
                    }
                    .foregroundStyle(C.text)
                    .frame(maxWidth: .infinity).frame(height: 50)
                    .background(Capsule().fill(C.tint))
                }
                .buttonStyle(.plain).padding(.top, 12)

                if cal.granted {
                    SectionLabel(text: tr("Write new plans to", "Новые планы записывать в"))
                    HStack(spacing: 12) {
                        Circle().fill(cal.writeCalendar.map { Color(cgColor: $0.cgColor) } ?? Color(hex: 0xEA4335)).frame(width: 12, height: 12)
                        Text(writeName).font(.inter(15.5, .semibold)).foregroundStyle(C.text)
                        Spacer()
                        Menu {
                            ForEach(cal.writable, id: \.calendarIdentifier) { c in
                                Button((c.source?.title ?? "") + " · " + c.title) { cal.setWrite(c) }
                            }
                        } label: { Text(tr("Change ›", "Изменить ›")).font(.inter(14)).foregroundStyle(C.sub) }
                    }
                    .padding(.horizontal, 16).padding(.vertical, 14).card()
                }

                SectionLabel(text: tr("Also count as busy", "Тоже считать занятым"))
                VStack(spacing: 0) {
                    SRow(title: tr("Sleep ", "Сон ") + Fmt.minuteOfDay(sleepFrom) + " – " + Fmt.minuteOfDay(sleepTo)) {
                        HStack(spacing: 10) {
                            Menu {
                                ForEach([(22 * 60, 6 * 60 + 30), (23 * 60, 7 * 60), (23 * 60 + 30, 7 * 60 + 30), (0, 8 * 60), (60, 9 * 60)], id: \.0) { a, b in
                                    Button(Fmt.minuteOfDay(a) + " – " + Fmt.minuteOfDay(b)) { sleepFrom = a; sleepTo = b }
                                }
                            } label: { Ic(name: "clock", size: 18, color: C.sub) }
                            PinkToggle(isOn: $sleepBusy)
                        }
                    }
                    SRow(title: tr("Travel time between places", "Дорога между местами"), last: true) { PinkToggle(isOn: $travelBusy) }
                }
                .padding(.horizontal, 16).padding(.vertical, 4).card()
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .screen("Подруга: connect calendars")
        .alert(tr("Add a calendar account", "Добавить календарь"), isPresented: $howTo) {
            Button(tr("Open Settings", "Открыть Настройки")) {
                if let u = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(u) }
            }
            Button("OK", role: .cancel) {}
        } message: {
            Text(tr("On the iPhone go to Settings › Apps › Calendar › Calendar Accounts › Add Account. Google, Outlook, Yahoo and others then show up here automatically.",
                    "На iPhone: Настройки › Приложения › Календарь › Учётные записи › Добавить. Google, Outlook, Yahoo и другие сразу появятся здесь."))
        }
    }

    var writeName: String {
        if cal.demo { return "Google · " + tr("Personal", "Личное") }
        guard let c = cal.writeCalendar else { return "—" }
        return (c.source?.title ?? "") + " · " + c.title
    }

    func binding(_ id: String) -> Binding<Bool> {
        Binding(get: {
            if cal.demo { return true }
            guard let a = cal.accounts.first(where: { $0.id == id }) else { return false }
            return cal.isOn(a)
        }, set: { v in
            if let a = cal.accounts.first(where: { $0.id == id }) { cal.set(a, on: v) }
            Task { await Notifier.shared.reschedule() }
        })
    }
}

struct NotificationsView: View {
    let back: String
    @AppStorage("remind") private var remind = "10,1440"
    @AppStorage("leaveOn") private var leaveOn = true
    @AppStorage("morningOn") private var morningOn = true
    @AppStorage("morningAt") private var morningAt = 8 * 60
    @AppStorage("eveningOn") private var eveningOn = true
    @AppStorage("eveningAt") private var eveningAt = 21 * 60
    @AppStorage("freeOn") private var freeOn = true
    @AppStorage("deadlineOn") private var deadlineOn = true
    @AppStorage("sound") private var sound = "chime"
    @AppStorage("breakFocus") private var breakFocus = true
    @AppStorage("quietOn") private var quietOn = true
    @AppStorage("quietFrom") private var quietFrom = 23 * 60
    @AppStorage("quietTo") private var quietTo = 7 * 60 + 30

    let offsets = [0, 10, 30, 60, 1440]

    var chosen: Set<Int> { Set(remind.split(separator: ",").compactMap { Int($0) }) }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                Header(back: back, title: tr("Notifications", "Уведомления"))
                SectionLabel(text: tr("Remind me", "Напоминать"))
                Flow(spacing: 6) {
                    ForEach(offsets, id: \.self) { o in
                        Button {
                            var s = chosen
                            if s.contains(o) { if s.count > 1 { s.remove(o) } } else { s.insert(o) }
                            remind = s.sorted().map(String.init).joined(separator: ",")
                            changed()
                        } label: { Chip(text: Fmt.remind(o), on: chosen.contains(o)) }.buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 12).padding(.horizontal, 16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .card()

                SectionLabel(text: tr("Nudges", "Подсказки"))
                VStack(spacing: 0) {
                    SRow(title: tr("Time to leave", "Пора выходить"), sub: tr("Uses travel time to the place", "С учётом дороги до места")) { PinkToggle(isOn: bind($leaveOn)) }
                    SRow(title: tr("Morning plan", "Утренний план"), sub: tr("Your day at a glance", "Весь день одним взглядом")) {
                        timeMenu(on: $morningOn, at: $morningAt, choices: Array(stride(from: 6 * 60, through: 11 * 60, by: 30)))
                    }
                    SRow(title: tr("Evening check", "Вечерняя проверка"), sub: tr("Move what's left to tomorrow", "Перенести несделанное на завтра")) {
                        timeMenu(on: $eveningOn, at: $eveningAt, choices: Array(stride(from: 18 * 60, through: 23 * 60, by: 30)))
                    }
                    SRow(title: tr("Free-time ideas", "Идеи на свободное время"), sub: tr("\"You have 1 h free – do it now?\"", "«У тебя свободный час — сделать сейчас?»")) { PinkToggle(isOn: bind($freeOn)) }
                    SRow(title: tr("Deadline warnings", "Предупреждать о сроках"), sub: tr("When a thing is getting close", "Когда срок уже близко"), last: true) { PinkToggle(isOn: bind($deadlineOn)) }
                }
                .padding(.horizontal, 16).padding(.vertical, 4).card()

                SectionLabel(text: tr("How it sounds", "Как звучит"))
                VStack(spacing: 0) {
                    SRow(title: tr("Sound", "Звук")) {
                        Menu {
                            Button(tr("Butterfly chime", "Бабочка")) { sound = "chime"; changed() }
                            Button(tr("iPhone default", "Стандартный")) { sound = "default"; changed() }
                            Button(tr("Silent", "Без звука")) { sound = "none"; changed() }
                        } label: { ValueText(text: soundName) }
                    }
                    SRow(title: tr("Important ones break through Focus", "Важные — даже в режиме «Фокус»"), sub: tr("Dentist, deadlines", "Стоматолог, сроки")) { PinkToggle(isOn: bind($breakFocus)) }
                    SRow(title: tr("Quiet hours", "Тихие часы"), last: true) {
                        Menu {
                            Button(tr("Off", "Выкл.")) { quietOn = false; changed() }
                            ForEach([(22 * 60, 7 * 60), (23 * 60, 7 * 60 + 30), (0, 8 * 60)], id: \.0) { a, b in
                                Button(Fmt.minuteOfDay(a) + " – " + Fmt.minuteOfDay(b)) { quietOn = true; quietFrom = a; quietTo = b; changed() }
                            }
                        } label: { ValueText(text: quietOn ? Fmt.minuteOfDay(quietFrom) + " – " + Fmt.minuteOfDay(quietTo) : tr("Off", "Выкл.")) }
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 4).card()

                Button {
                    Notifier.shared.add(.init(id: "test", title: tr("Time to leave", "Пора выходить"),
                                              body: tr("This is how \(appName) sounds", "Так звучит \(appName)"),
                                              date: Date().addingTimeInterval(5), category: "LEAVE"))
                } label: {
                    Text(tr("Send a test in 5 seconds", "Тест через 5 секунд")).font(.inter(13.5, .semibold)).foregroundStyle(C.pink)
                        .frame(maxWidth: .infinity).padding(.vertical, 16)
                }.buttonStyle(.plain)
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .screen("Подруга: notification options")
    }

    var soundName: String {
        switch sound {
        case "none": return tr("Silent", "Без звука")
        case "default": return tr("iPhone default", "Стандартный")
        default: return tr("Butterfly chime", "Бабочка")
        }
    }

    func changed() { Task { await Notifier.shared.reschedule() } }

    func bind(_ b: Binding<Bool>) -> Binding<Bool> {
        Binding(get: { b.wrappedValue }, set: { b.wrappedValue = $0; changed() })
    }

    func timeMenu(on: Binding<Bool>, at: Binding<Int>, choices: [Int]) -> some View {
        Menu {
            Button(tr("Off", "Выкл.")) { on.wrappedValue = false; changed() }
            ForEach(choices, id: \.self) { m in
                Button(Fmt.minuteOfDay(m)) { on.wrappedValue = true; at.wrappedValue = m; changed() }
            }
        } label: { ValueText(text: on.wrappedValue ? Fmt.minuteOfDay(at.wrappedValue) : tr("Off", "Выкл.")) }
    }
}
