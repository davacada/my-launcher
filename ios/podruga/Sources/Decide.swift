import SwiftUI

struct DecideView: View {
    @EnvironmentObject var store: Store
    @State private var addingOption = false
    @State private var addingCriterion = false
    @State private var newText = ""
    @State private var thinking = false
    @FocusState private var focused: Bool

    var d: Decision { store.decision }

    static var defaultCriteria: [String] {
        [tr("Price", "Цена"), tr("Time", "Время"), tr("Quality", "Качество"), tr("Distance", "Расстояние"), tr("Comfort", "Удобство")]
    }

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .firstTextBaseline) {
                    Text(tr("DECIDE", "РЕШИТЬ")).font(.inter(13, .semibold)).foregroundStyle(C.sub)
                    Spacer()
                    if !d.question.isEmpty || !d.options.isEmpty {
                        Button {
                            store.decision = Decision(criteria: DecideView.defaultCriteria)
                        } label: {
                            Text(tr("New", "Новый")).font(.inter(13, .semibold)).foregroundStyle(C.pink)
                        }.buttonStyle(.plain)
                    }
                }
                TextField(tr("What can't you decide?", "Что не можешь решить?"), text: Binding(
                    get: { store.decision.question },
                    set: { store.decision.question = $0; store.decision.pick = nil }), axis: .vertical)
                    .font(.inter(27, .heavy)).tracking(-0.3).foregroundStyle(C.text)
                    .lineLimit(1...4).focused($focused).submitLabel(.done).onSubmit { focused = false }
                    .padding(.top, 4).padding(.bottom, 16)

                Flow(spacing: 8) {
                    ForEach(d.options, id: \.self) { o in
                        Text(o).font(.inter(13, .semibold)).foregroundStyle(C.text)
                            .padding(.horizontal, 13).padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white))
                            .contextMenu {
                                Button(role: .destructive) {
                                    store.decision.options.removeAll { $0 == o }; store.decision.pick = nil
                                } label: { Label(tr("Remove", "Удалить"), systemImage: "trash") }
                            }
                    }
                    Button { newText = ""; addingOption = true } label: {
                        HStack(spacing: 4) {
                            Ic(name: "plus", size: 14, color: C.pink)
                            Text(d.options.isEmpty ? tr("Add an option", "Добавить вариант") : tr("Option", "Вариант"))
                                .font(.inter(13, .semibold)).foregroundStyle(C.pink)
                        }
                        .padding(.horizontal, 13).padding(.vertical, 8)
                        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(C.pink.opacity(0.5), style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                    }.buttonStyle(.plain)
                }
                .padding(.bottom, 14)

                Text(tr("WHAT MATTERS", "ЧТО ВАЖНО")).font(.inter(12, .semibold)).tracking(0.7).foregroundStyle(C.sub).padding(.bottom, 8)
                Flow(spacing: 8) {
                    ForEach(d.criteria, id: \.self) { c in
                        let on = d.chosen.contains(c)
                        Button {
                            if on { store.decision.chosen.removeAll { $0 == c } } else { store.decision.chosen.append(c) }
                            store.decision.pick = nil
                        } label: {
                            Text(c).font(.inter(12.5, .semibold)).foregroundStyle(on ? C.text : C.sub)
                                .padding(.horizontal, 12).padding(.vertical, 7)
                                .background {
                                    if on { RoundedRectangle(cornerRadius: 14, style: .continuous).fill(C.grad) }
                                    else { RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(C.text.opacity(0.15), lineWidth: 1) }
                                }
                        }.buttonStyle(.plain)
                    }
                    Button { newText = ""; addingCriterion = true } label: {
                        Ic(name: "plus", size: 14, color: C.sub)
                            .padding(.horizontal, 10).padding(.vertical, 8)
                            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(C.text.opacity(0.15), lineWidth: 1))
                    }.buttonStyle(.plain)
                }
                .padding(.bottom, 18)

                if let pick = d.pick {
                    result(pick)
                } else if thinking {
                    HStack(spacing: 10) {
                        ProgressView().tint(C.pink)
                        Text(tr("\(appName) is thinking…", "\(appName) думает…")).font(.inter(14)).foregroundStyle(C.sub)
                    }.padding(16).frame(maxWidth: .infinity, alignment: .leading).card()
                } else if d.options.count >= 2 {
                    if !AI.available { RatingGrid().padding(.bottom, 14) }
                    PillButton(title: tr("Ask \(appName)", "Спросить \(appName)"), dark: true) { ask() }
                        .disabled(d.chosen.isEmpty)
                } else {
                    Text(tr("Add at least two options, choose what matters, and \(appName) will tell you more or less the best one.",
                            "Добавь хотя бы два варианта, отметь, что важно, и \(appName) подскажет лучший."))
                        .font(.inter(13.5)).foregroundStyle(C.sub).lineSpacing(3)
                }
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 130)
        }
        .scrollDismissesKeyboard(.interactively)
        .screen("Подруга: help me decide")
        .onAppear { if store.decision.criteria.isEmpty { store.decision.criteria = DecideView.defaultCriteria } }
        .alert(tr("Add an option", "Добавить вариант"), isPresented: $addingOption) {
            TextField(tr("e.g. Telemach · €12", "например, Telemach · €12"), text: $newText)
            Button(tr("Add", "Добавить")) {
                let s = newText.trimmingCharacters(in: .whitespaces)
                if !s.isEmpty, !store.decision.options.contains(s) { store.decision.options.append(s); store.decision.pick = nil }
            }
            Button(tr("Cancel", "Отмена"), role: .cancel) {}
        }
        .alert(tr("What matters?", "Что важно?"), isPresented: $addingCriterion) {
            TextField(tr("e.g. EU roaming", "например, роуминг"), text: $newText)
            Button(tr("Add", "Добавить")) {
                let s = newText.trimmingCharacters(in: .whitespaces)
                if !s.isEmpty, !store.decision.criteria.contains(s) { store.decision.criteria.append(s); store.decision.chosen.append(s) }
            }
            Button(tr("Cancel", "Отмена"), role: .cancel) {}
        }
    }

    func result(_ pick: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    Ic(name: "spark", size: 15)
                    Text(tr("\(appName) says", "\(appName) советует").uppercased()).font(.inter(12, .bold)).tracking(0.7)
                }.opacity(0.9)
                Text(pick).font(.inter(23, .heavy)).padding(.top, 8)
                if let why = d.why { Text(why).font(.inter(13.5)).lineSpacing(3).opacity(0.9).padding(.top, 4) }
            }
            .foregroundStyle(C.text)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(18)
            .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(C.grad))

            VStack(spacing: 9) {
                ForEach(d.options.sorted { (d.scores[$0] ?? 0) > (d.scores[$1] ?? 0) }, id: \.self) { o in
                    let v = d.scores[o] ?? 0
                    HStack(spacing: 10) {
                        Text(o.components(separatedBy: " · ").first ?? o).font(.inter(13)).foregroundStyle(C.sub)
                            .frame(width: 84, alignment: .leading).lineLimit(1)
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(C.text.opacity(0.08))
                                Capsule().fill(C.grad).frame(width: g.size.width * CGFloat(v) / 10)
                            }
                        }.frame(height: 8)
                        Text("\(v)").font(.inter(12)).foregroundStyle(C.text).frame(width: 26, alignment: .trailing)
                    }
                }
            }
            .padding(.top, 14)
            if !AI.available {
                Button { store.decision.pick = nil } label: {
                    Text(tr("Change my ratings", "Изменить оценки")).font(.inter(13.5, .semibold)).foregroundStyle(C.pink)
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                }.buttonStyle(.plain)
            } else {
                Button { ask() } label: {
                    Text(tr("Ask again", "Спросить ещё раз")).font(.inter(13.5, .semibold)).foregroundStyle(C.pink)
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                }.buttonStyle(.plain)
            }
        }
    }

    func ask() {
        focused = false
        if !AI.available {
            if let r = LocalBrain.decide(store.decision) {
                store.decision.why = r.why
                store.decision.scores = r.scores
                store.decision.pick = r.pick
            }
            return
        }
        thinking = true
        store.decision.pick = nil
        let snapshot = store.decision
        Task {
            if let r = await AI.decide(snapshot) {
                store.decision.pick = r.pick
                store.decision.why = r.why
                store.decision.scores = r.scores
            }
            thinking = false
        }
    }
}

struct ThingsView: View {
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    @State private var showDone = false

    var body: some View {
        let planned = store.things.filter { !$0.done && $0.start != nil }.sorted { $0.start! < $1.start! }
        let open = store.things.filter { !$0.done && $0.start == nil }.sorted { ($0.deadline ?? .distantFuture) < ($1.deadline ?? .distantFuture) }
        let done = store.things.filter { $0.done }.sorted { $0.created > $1.created }
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(tr("THINGS", "ДЕЛА")).font(.inter(13, .semibold)).foregroundStyle(C.sub)
                        Text(tr("All things", "Все дела")).font(.inter(30, .heavy)).tracking(-0.6).foregroundStyle(C.text).padding(.top, 4)
                    }
                    Spacer()
                    Button { router.push(.capture("", nil)) } label: {
                        ZStack { Circle().fill(C.grad).frame(width: 38, height: 38); Ic(name: "plus", size: 20) }
                    }.buttonStyle(.plain)
                }
                if store.things.isEmpty {
                    Text(tr("Nothing yet. Write a thing on Today, or say it with the mic.", "Пока пусто. Напиши дело на экране «Сегодня» или скажи его в микрофон."))
                        .font(.inter(14)).foregroundStyle(C.sub).padding(.top, 20)
                }
                if !planned.isEmpty {
                    SectionLabel(text: tr("Planned", "Запланировано"))
                    ForEach(planned) { t in ThingRow(t: t) }
                }
                if !open.isEmpty {
                    SectionLabel(text: tr("Not planned yet", "Ещё без времени"))
                    ForEach(open) { t in ThingRow(t: t) }
                }
                if !done.isEmpty {
                    Button { withAnimation { showDone.toggle() } } label: {
                        SectionLabel(text: tr("Done", "Готово") + " · \(done.count)")
                    }.buttonStyle(.plain)
                    if showDone { ForEach(done) { t in ThingRow(t: t) } }
                }
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 130)
        }
        .screen("Подруга: things")
    }
}

struct ThingRow: View {
    let t: Thing
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    var sub: String {
        if let s = t.start { return Fmt.when(s) + (t.place.map { " · " + $0 } ?? "") }
        if let d = t.deadline { return tr("before ", "до ") + Fmt.dayMonth(d) + " · " + Fmt.dur(t.minutes) }
        return Fmt.dur(t.minutes)
    }
    var body: some View {
        HStack(spacing: 12) {
            Button {
                var x = t; x.done.toggle(); store.upsert(x)
                Task { await Notifier.shared.reschedule() }
            } label: {
                ZStack {
                    Circle().strokeBorder(t.done ? Color.clear : C.pink.opacity(0.6), lineWidth: 2)
                        .background(Circle().fill(t.done ? AnyShapeStyle(C.grad) : AnyShapeStyle(Color.clear)))
                        .frame(width: 26, height: 26)
                    if t.done { Ic(name: "check", size: 16) }
                }
            }.buttonStyle(.plain)
            Button { router.push(.thing(t.id)) } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(t.title).font(.inter(15.5, .semibold)).foregroundStyle(C.text).strikethrough(t.done).lineLimit(2)
                        Text(sub).font(.inter(12.5)).foregroundStyle(C.sub).lineLimit(1)
                    }
                    Spacer()
                    if !t.attachments.isEmpty {
                        HStack(spacing: 3) { Ic(name: "file", size: 14, color: C.sub); Text("\(t.attachments.count)").font(.inter(12)).foregroundStyle(C.sub) }
                    }
                    Ic(name: "chev", size: 15, color: C.sub)
                }
                .contentShape(Rectangle())
            }.buttonStyle(.plain)
        }
        .padding(.horizontal, 14).padding(.vertical, 13)
        .card(18)
        .padding(.bottom, 8)
        .opacity(t.done ? 0.6 : 1)
    }
}

/// On iPhones without Apple Intelligence she rates each option, 1–5, for what matters.
struct RatingGrid: View {
    @EnvironmentObject var store: Store
    var d: Decision { store.decision }
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(tr("RATE THEM", "ОЦЕНИ ВАРИАНТЫ")).font(.inter(12, .semibold)).tracking(0.7).foregroundStyle(C.sub).padding(.bottom, 8)
            VStack(spacing: 0) {
                ForEach(Array(d.chosen.enumerated()), id: \.element) { ci, c in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(c).font(.inter(14, .bold)).foregroundStyle(C.text)
                        ForEach(d.options, id: \.self) { o in
                            HStack(spacing: 8) {
                                Text(o.components(separatedBy: " · ").first ?? o).font(.inter(13)).foregroundStyle(C.sub)
                                    .lineLimit(1).frame(maxWidth: .infinity, alignment: .leading)
                                ForEach(1...5, id: \.self) { v in
                                    let cur = d.ratings[c]?[o] ?? 3
                                    Button {
                                        var r = store.decision.ratings[c] ?? [:]
                                        r[o] = v
                                        store.decision.ratings[c] = r
                                    } label: {
                                        Circle().fill(v <= cur ? AnyShapeStyle(C.grad) : AnyShapeStyle(C.text.opacity(0.08)))
                                            .frame(width: 22, height: 22)
                                            .frame(width: 30, height: 30)
                                            .contentShape(Rectangle())
                                    }.buttonStyle(.plain)
                                }
                            }
                        }
                    }
                    .padding(.vertical, 12)
                    if ci < d.chosen.count - 1 { Rectangle().fill(C.line).frame(height: 1) }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 4).card()
            Text(tr("Tap the dots: more pink = better. \(appName) then picks the best one.",
                    "Нажимай на точки: больше розовых — лучше. Потом \(appName) выберет лучший."))
                .font(.inter(12)).foregroundStyle(C.sub).padding(.top, 8).padding(.horizontal, 4)
        }
    }
}
