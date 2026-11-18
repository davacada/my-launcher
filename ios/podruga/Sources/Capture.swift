import SwiftUI

struct CaptureView: View {
    let initial: String
    let preset: Date?
    let back: String

    @EnvironmentObject var cal: Cal
    @EnvironmentObject var router: Router
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var parsed = Parsed()
    @State private var ai: AIThing?
    @State private var thinking = false
    @State private var chosen: Date?
    @State private var picking = false
    @State private var pickDate = Date()
    @State private var saving = false
    @FocusState private var focused: Bool

    init(initial: String, preset: Date?, back: String) {
        self.initial = initial; self.preset = preset; self.back = back
        _text = State(initialValue: initial)
    }

    var minutes: Int { parsed.minutes ?? ai?.minutes ?? LocalBrain.minutes(text) ?? 30 }

    /// The time to do it: chosen by her, said in the text, or the best free time.
    var slot: Date? {
        if let c = chosen { return c }
        if let s = parsed.start { return s }
        if let d = parsed.day {
            return cal.bestSlot(minutes: minutes, deadline: d.addingTimeInterval(86400 - 60)) ?? Calendar.current.date(bySettingHour: 10, minute: 0, second: 0, of: d)
        }
        return cal.bestSlot(minutes: minutes, deadline: parsed.deadline)
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                BackButton(title: back).padding(.bottom, 22)

                TextField(tr("Write a thing…", "Напиши дело…"), text: $text, axis: .vertical)
                    .font(.inter(27, .bold))
                    .tracking(-0.2)
                    .foregroundStyle(C.text)
                    .lineLimit(1...8)
                    .focused($focused)
                    .submitLabel(.done)
                    .onSubmit { focused = false }

                if !text.trimmingCharacters(in: .whitespaces).isEmpty {
                    understood
                    bestTime.padding(.top, 14)
                    HStack(spacing: 10) {
                        PillButton(title: tr("Other time", "Другое время"), soft: true) {
                            pickDate = slot ?? Date().addingTimeInterval(3600); picking = true
                        }
                        PillButton(title: tr("Plan it", "Запланировать"), dark: true) { plan() }
                            .layoutPriority(1.4)
                            .disabled(saving)
                    }
                    .padding(.top, 16)
                    Button { planLater() } label: {
                        Text(tr("Just keep it, no time yet", "Просто сохранить, без времени"))
                            .font(.inter(13.5, .semibold)).foregroundStyle(C.sub)
                            .frame(maxWidth: .infinity).padding(.vertical, 14)
                    }.buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .scrollDismissesKeyboard(.interactively)
        .screen("Подруга: write a thing")
        .onAppear {
            chosen = preset
            update()
            if initial.isEmpty { focused = true }
            if !initial.isEmpty { askAI() }
        }
        .onChange(of: text) { _, _ in update(); askAI() }
        .sheet(isPresented: $picking) { picker }
    }

    var understood: some View {
        VStack(alignment: .leading, spacing: 0) {
            SectionLabel(text: tr("\(appName) understood", "\(appName) поняла")).padding(.top, 4)
            VStack(spacing: 0) {
                let rows = infoRows
                ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                    InfoRow(icon: r.0, label: r.1, value: r.2, last: i == rows.count - 1)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 4)
            .card()
        }
    }

    var infoRows: [(String, String, String)] {
        var rows: [(String, String, String)] = []
        if let d = parsed.deadline {
            rows.append(("cal", tr("Deadline", "Срок"), tr("before ", "до ") + Fmt.dayMonth(d)))
        } else if let s = parsed.start {
            rows.append(("cal", tr("When", "Когда"), Fmt.when(s)))
        } else if let d = parsed.day {
            rows.append(("cal", tr("When", "Когда"), Fmt.shortDay(d)))
        }
        rows.append(("clock", tr("Takes", "Займёт"), tr("about ", "около ") + Fmt.dur(minutes) + (thinking && ai == nil ? " …" : "")))
        if let p = parsed.place ?? ((ai?.place.isEmpty == false) ? ai?.place : nil) {
            rows.append(("pin", tr("Where", "Где"), p))
        }
        let offs = Pref.remindOffsets
        rows.append(("bell", tr("Remind", "Напомнить"), Fmt.remind(offs.last ?? 10).lowercased()))
        if let s = (ai?.firstStep).flatMap({ $0.isEmpty ? nil : $0 }) ?? LocalBrain.firstStep(text) { rows.append(("spark", tr("First step", "Первый шаг"), s)) }
        return rows
    }

    var bestTime: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let s = slot {
                let e = s.addingTimeInterval(Double(minutes) * 60)
                Text(chosen != nil || parsed.start != nil ? tr("YOUR TIME", "ТВОЁ ВРЕМЯ") : tr("BEST TIME IN YOUR CALENDAR", "ЛУЧШЕЕ ВРЕМЯ В КАЛЕНДАРЕ"))
                    .font(.inter(12, .bold)).tracking(0.7).opacity(0.85)
                Text(Fmt.shortDay(s) + " · " + Fmt.time(s) + " – " + Fmt.time(e))
                    .font(.inter(21, .bold)).padding(.top, 6)
                Text(reason(s, e)).font(.inter(13)).opacity(0.85).padding(.top, 2)
            } else {
                Text(tr("NO FREE TIME FOUND", "НЕТ СВОБОДНОГО ВРЕМЕНИ")).font(.inter(12, .bold)).tracking(0.7).opacity(0.85)
                Text(tr("Pick a time yourself", "Выбери время сама")).font(.inter(21, .bold)).padding(.top, 6)
            }
        }
        .foregroundStyle(C.text)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(C.grad))
    }

    func reason(_ s: Date, _ e: Date) -> String {
        let clash = cal.events(from: s, to: e).first
        if let c = clash { return tr("Clashes with \(c.title)", "Пересекается с: \(c.title)") }
        return cal.reason(for: s)
    }

    var picker: some View {
        NavigationStack {
            DatePicker("", selection: $pickDate, in: Date()..., displayedComponents: [.date, .hourAndMinute])
                .datePickerStyle(.graphical)
                .environment(\.locale, Lang.locale)
                .padding()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel", "Отмена")) { picking = false } }
                    ToolbarItem(placement: .confirmationAction) { Button(tr("Done", "Готово")) { chosen = pickDate; picking = false } }
                }
        }
        .presentationDetents([.medium, .large])
    }

    func update() { parsed = Parser.parse(text, now: cal.now) }

    func askAI() {
        guard AI.available else { return }
        let snapshot = text
        thinking = true
        Task {
            try? await Task.sleep(nanoseconds: 900_000_000)
            guard snapshot == text, !snapshot.isEmpty else { return }
            let r = await AI.understand(snapshot, now: cal.now)
            if snapshot == text { ai = r; thinking = false }
        }
    }

    func plan() {
        saving = true
        let t = Planner.make(text: text, parsed: parsed, ai: ai, start: slot)
        Task {
            await Planner.commit(t)
            router.paths[router.tab] = []
        }
    }

    func planLater() {
        let t = Planner.make(text: text, parsed: parsed, ai: ai, start: nil)
        Task {
            await Planner.commit(t)
            router.paths[router.tab] = []
        }
    }
}

// MARK: - Voice → reminder

struct VoiceView: View {
    @EnvironmentObject var cal: Cal
    @EnvironmentObject var router: Router
    @Environment(\.dismiss) private var dismiss
    @StateObject private var rec = VoiceRec()
    @State private var ai: AIThing?

    var parsed: Parsed { Parser.parse(rec.text, now: cal.now) }

    var body: some View {
        let p = parsed
        ZStack(alignment: .bottom) {
            ScrollView(showsIndicators: false) {
                VStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Text(rec.running ? tr("LISTENING… ", "СЛУШАЮ… ") + clock : tr("STOPPED · ", "ГОТОВО · ") + clock)
                            .font(.inter(13, .semibold)).foregroundStyle(C.sub)
                        Spacer()
                        Button { rec.stop(); rec.reset(); dismiss() } label: {
                            Ic(name: "x", size: 18, color: C.sub).frame(width: 34, height: 34).background(Circle().fill(Color.white.opacity(0.8)))
                        }.buttonStyle(.plain)
                    }
                    Text(transcript(p))
                        .font(.inter(21, .semibold)).lineSpacing(5).foregroundStyle(C.text)
                        .padding(.top, 10)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let prob = rec.problem {
                        Text(prob).font(.inter(13)).foregroundStyle(C.pink).padding(.top, 8)
                    }
                    if !rec.text.isEmpty {
                        SectionLabel(text: tr("\(appName) made a reminder", "\(appName) сделала напоминание"))
                        VStack(spacing: 0) {
                            let rows = reminderRows(p)
                            ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                                InfoRow(icon: r.0, label: r.1, value: r.2, last: i == rows.count - 1)
                            }
                        }
                        .padding(.horizontal, 16).padding(.vertical, 4)
                        .card()
                    }
                }
                .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 260)
            }

            VStack(spacing: 0) {
                HStack(alignment: .center, spacing: 3) {
                    ForEach(Array(rec.levels.enumerated()), id: \.offset) { _, h in
                        Capsule().fill(C.pink).frame(width: 4, height: rec.running ? h : 6)
                    }
                }
                .frame(height: 34).padding(.bottom, 18)
                .animation(.easeOut(duration: 0.12), value: rec.levels)
                HStack {
                    Button { edit() } label: {
                        Text(tr("Edit", "Изменить")).font(.inter(16, .semibold)).foregroundStyle(C.text)
                            .frame(width: 84, height: 52).background(Capsule().fill(Color.white))
                            .shadow(color: C.text.opacity(0.06), radius: 7, y: 4)
                    }.buttonStyle(.plain).disabled(rec.text.isEmpty)
                    Spacer()
                    Button {
                        if rec.running { rec.stop() } else { rec.reset(); Task { await rec.start() } }
                    } label: {
                        ZStack {
                            Circle().fill(C.p1.opacity(0.1)).frame(width: 144, height: 144)
                            Circle().fill(C.p1.opacity(0.25)).frame(width: 120, height: 120)
                            Circle().fill(C.grad).frame(width: 96, height: 96)
                            if rec.running {
                                RoundedRectangle(cornerRadius: 8).fill(C.text).frame(width: 30, height: 30)
                            } else {
                                Ic(name: "mic", size: 38, color: C.text)
                            }
                        }
                        .frame(width: 96, height: 96)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(rec.running ? tr("Stop recording", "Остановить") : tr("Record", "Записать"))
                    Spacer()
                    Button { save() } label: {
                        Text(tr("Save", "Сохранить")).font(.inter(16, .bold)).foregroundStyle(C.bg)
                            .frame(width: 84, height: 52).background(Capsule().fill(C.text))
                            .minimumScaleFactor(0.7)
                    }.buttonStyle(.plain).disabled(rec.text.isEmpty)
                }
                Text(rec.running ? tr("Tap the square to stop", "Нажми на квадрат, чтобы остановить")
                                 : tr("Tap to record again", "Нажми, чтобы записать заново"))
                    .font(.inter(12.5)).foregroundStyle(C.sub).padding(.top, 14)
            }
            .padding(.horizontal, 24).padding(.bottom, 30)
        }
        .background(Backdrop(seed: "Подруга: voice becomes a reminder"))
        .task { rec.demo = Store.shared.demo; await rec.start() }
        .onChange(of: rec.running) { _, r in
            if !r, AI.available, !rec.text.isEmpty {
                let t = rec.text
                Task { ai = await AI.understand(t, now: cal.now) }
            }
        }
        .onDisappear { rec.stop() }
    }

    var clock: String { String(format: "%d:%02d", rec.seconds / 60, rec.seconds % 60) }

    func transcript(_ p: Parsed) -> AttributedString {
        if rec.text.isEmpty { return AttributedString(tr("Say the thing…", "Скажи, что нужно сделать…")) }
        var a = AttributedString("“" + rec.text + "”")
        for h in p.highlights {
            if let r = a.range(of: h, options: .caseInsensitive) {
                a[r].backgroundColor = C.soft
            }
        }
        return a
    }

    func reminderRows(_ p: Parsed) -> [(String, String, String)] {
        var rows: [(String, String, String)] = [("pen", tr("What", "Что"), ai?.title ?? p.title)]
        if let s = p.start { rows.append(("cal", tr("When", "Когда"), Fmt.when(s))) }
        else if let d = p.deadline { rows.append(("cal", tr("By", "До"), Fmt.shortDay(d))) }
        else if let d = p.day { rows.append(("cal", tr("When", "Когда"), Fmt.shortDay(d))) }
        if let pl = p.place ?? ((ai?.place.isEmpty == false) ? ai?.place : nil) { rows.append(("pin", tr("Where", "Где"), pl)) }
        if let s = p.start, p.place != nil {
            let leave = s.addingTimeInterval(-Double(Store.shared.demo ? 40 : 30) * 60)
            rows.append(("bell", tr("Remind", "Напомнить"), tr("at ", "в ") + Fmt.time(leave) + tr(" · time to leave", " · пора выходить")))
        } else {
            rows.append(("bell", tr("Remind", "Напомнить"), Fmt.remind(Pref.remindOffsets.first ?? 10).lowercased()))
        }
        return rows
    }

    func edit() {
        rec.stop()
        let t = rec.text
        dismiss()
        router.tab = 0
        router.paths[0] = [.capture(t, nil)]
    }

    func save() {
        rec.stop()
        let p = parsed
        let start = p.start ?? p.day.flatMap { cal.bestSlot(minutes: p.minutes ?? 30, deadline: $0.addingTimeInterval(86340)) }
            ?? (p.deadline != nil ? cal.bestSlot(minutes: p.minutes ?? 30, deadline: p.deadline) : nil)
        var t = Planner.make(text: rec.text, parsed: p, ai: ai, start: start)
        if let u = rec.fileURL {
            t.attachments.append(Attachment(kind: .voice, file: u.lastPathComponent, title: tr("Voice note", "Голосовая заметка"),
                                            seconds: audioSeconds(u)))
        }
        Task { await Planner.commit(t) }
        dismiss()
    }
}
