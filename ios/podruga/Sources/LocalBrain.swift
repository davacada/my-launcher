import Foundation

/// Suggestions that work on every iPhone (iPhone 14 included), without Apple's AI.
enum LocalBrain {
    struct Kind {
        let id: String
        let words: [String]
        let minutes: Int
        let firstStep: () -> String
        let ways: () -> [(String, String, String, String, Int, Double)]  // name, cost, time, doneBy, minutes, euros
    }

    static let kinds: [Kind] = [
        Kind(id: "gift", words: ["gift", "present", "buy", "order", "shop", "подар", "купить", "куп", "заказ"], minutes: 30,
             firstStep: { tr("make a short list of ideas", "составить список идей") },
             ways: { [
                (tr("Order online", "Заказать онлайн"), tr("usual price, free delivery", "обычная цена, доставка"), tr("15 min", "15 мин"), tr("in 2–3 days", "через 2–3 дня"), 15, 1),
                (tr("Shop in town", "Купить в городе"), tr("usual price + bus", "цена + проезд"), tr("1 h 30 with travel", "1 ч 30 с дорогой"), tr("today", "сегодня"), 90, 1.3),
                (tr("Make something", "Сделать самой"), tr("cheapest", "дешевле всего"), tr("2–3 h", "2–3 ч"), tr("needs a free evening", "нужен свободный вечер"), 150, 0),
             ] }),
        Kind(id: "docs", words: ["passport", "id card", "licen", "permit", "form", "document", "visa", "паспорт", "документ", "справк", "виз", "права", "заявлен"], minutes: 45,
             firstStep: { tr("book an appointment", "записаться на приём") },
             ways: { [
                (tr("Book online, then go", "Записаться онлайн"), tr("fee only", "только пошлина"), tr("10 min + 45 min visit", "10 мин + 45 мин"), tr("within a week", "в течение недели"), 55, 0),
                (tr("Go without booking", "Прийти без записи"), tr("fee only", "только пошлина"), tr("1–2 h queue", "1–2 ч в очереди"), tr("today", "сегодня"), 120, 0),
                (tr("Ask what's needed first", "Сначала узнать, что нужно"), "€0", tr("10 min call", "10 мин звонок"), tr("today", "сегодня"), 10, 0),
             ] }),
        Kind(id: "health", words: ["doctor", "dentist", "zdravnik", "zobozdravnik", "clinic", "врач", "стоматолог", "зубн", "клиник", "анализ"], minutes: 45,
             firstStep: { tr("call to book", "позвонить и записаться") },
             ways: { [
                (tr("Book online", "Записаться онлайн"), tr("€0 with insurance", "€0 по страховке"), tr("5 min", "5 мин"), tr("first free slot", "первое свободное время"), 5, 0),
                (tr("Call them", "Позвонить"), tr("€0 with insurance", "€0 по страховке"), tr("10 min", "10 мин"), tr("today, office hours", "сегодня, в часы работы"), 10, 0),
                (tr("Go private", "Частная клиника"), tr("€40–80", "€40–80"), tr("5 min", "5 мин"), tr("this week", "на этой неделе"), 5, 60),
             ] }),
        Kind(id: "call", words: ["call", "phone", "ring", "email", "write to", "message", "позвон", "звон", "написать", "письмо"], minutes: 10,
             firstStep: { tr("find the number", "найти номер") },
             ways: { [
                (tr("Call now", "Позвонить сейчас"), "€0", tr("5 min", "5 мин"), tr("right away", "сразу"), 5, 0),
                (tr("Send a message", "Написать сообщение"), "€0", tr("2 min", "2 мин"), tr("when they answer", "когда ответят"), 2, 0),
                (tr("Email with details", "Письмо с деталями"), "€0", tr("10 min", "10 мин"), tr("in 1–2 days", "через 1–2 дня"), 10, 0),
             ] }),
        Kind(id: "study", words: ["study", "exam", "homework", "essay", "seminar", "learn", "read", "assignment", "учить", "экзамен", "домашн", "реферат", "курсов", "прочитать", "семинар"], minutes: 90,
             firstStep: { tr("open the notes and pick a chapter", "открыть конспект и выбрать тему") },
             ways: { [
                (tr("Short sessions", "Короткие подходы"), "€0", tr("4 × 25 min", "4 × 25 мин"), tr("over 2 days", "за 2 дня"), 100, 0),
                (tr("One long session", "Одним заходом"), "€0", tr("2 h", "2 ч"), tr("today", "сегодня"), 120, 0),
                (tr("Study with a friend", "Учить с подругой"), tr("€0 + coffee", "€0 + кофе"), tr("2 h", "2 ч"), tr("when you're both free", "когда обе свободны"), 120, 3),
             ] }),
        Kind(id: "food", words: ["groceries", "food", "cook", "dinner", "lunch", "shopping list", "продукт", "еда", "готов", "ужин", "обед"], minutes: 45,
             firstStep: { tr("write the shopping list", "написать список покупок") },
             ways: { [
                (tr("Shop and cook", "Купить и приготовить"), "€8–12", tr("1 h", "1 ч"), tr("tonight", "сегодня вечером"), 60, 10),
                (tr("Order delivery", "Заказать доставку"), "€15–20", tr("5 min", "5 мин"), tr("in 40 min", "через 40 мин"), 5, 18),
                (tr("Cook for 3 days", "Наготовить на 3 дня"), "€15", tr("1 h 30", "1 ч 30"), tr("tonight", "сегодня вечером"), 90, 15),
             ] }),
        Kind(id: "trip", words: ["trip", "ticket", "travel", "flight", "train", "bus to", "поездк", "билет", "поезд", "самолет", "самолёт", "автобус до"], minutes: 30,
             firstStep: { tr("compare prices for your dates", "сравнить цены на даты") },
             ways: { [
                (tr("Bus (Flixbus)", "Автобус (Flixbus)"), tr("cheapest", "дешевле всего"), tr("20 min to book", "20 мин на покупку"), tr("book now, prices rise", "купить сейчас, цены растут"), 20, 15),
                (tr("Train", "Поезд"), tr("middle", "средне"), tr("20 min to book", "20 мин на покупку"), tr("book this week", "купить на этой неделе"), 20, 30),
                (tr("Share a ride", "Попутка (prevoz.org)"), tr("cheap", "дёшево"), tr("15 min", "15 мин"), tr("a few days before", "за пару дней"), 15, 10),
             ] }),
        Kind(id: "fix", words: ["fix", "repair", "broken", "replace", "починить", "сломал", "ремонт", "заменить"], minutes: 60,
             firstStep: { tr("look up the problem", "найти, в чём проблема") },
             ways: { [
                (tr("Fix it yourself", "Починить самой"), tr("parts only", "только детали"), tr("1 h with a video", "1 ч с видео"), tr("today", "сегодня"), 60, 5),
                (tr("Repair shop", "Мастерская"), "€20–60", tr("30 min to drop off", "30 мин отнести"), tr("in a few days", "через несколько дней"), 30, 40),
                (tr("Replace it", "Купить новое"), tr("most expensive", "дороже всего"), tr("20 min", "20 мин"), tr("in 2–3 days", "через 2–3 дня"), 20, 80),
             ] }),
        Kind(id: "clean", words: ["clean", "laundry", "wash", "tidy", "dishes", "убрать", "уборк", "стирк", "помыть", "посуд"], minutes: 40,
             firstStep: { tr("start with one corner", "начать с одного угла") },
             ways: { [
                (tr("Do it in one go", "Всё за раз"), "€0", tr("40 min", "40 мин"), tr("today", "сегодня"), 40, 0),
                (tr("10 minutes a day", "По 10 минут в день"), "€0", tr("10 min a day", "10 мин в день"), tr("over 4 days", "за 4 дня"), 10, 0),
                (tr("Split with flatmates", "Разделить с соседями"), "€0", tr("15 min", "15 мин"), tr("this week", "на этой неделе"), 15, 0),
             ] }),
    ]

    static func kind(_ text: String) -> Kind? {
        let low = text.lowercased()
        return kinds.first { k in k.words.contains { low.contains($0) } }
    }

    static func minutes(_ text: String) -> Int? { kind(text)?.minutes }
    static func firstStep(_ text: String) -> String? { kind(text)?.firstStep() }

    /// Three ways, the best one picked by how close the deadline is.
    static func ways(for t: Thing, now: Date) -> ([WayOption], String) {
        let k = kind(t.raw + " " + t.title)
        var raw = k?.ways() ?? [
            (tr("Do it now", "Сделать сейчас"), "€0", Fmt.dur(t.minutes), tr("today", "сегодня"), t.minutes, 0),
            (tr("Split into small steps", "Разбить на шаги"), "€0", tr("15 min a day", "15 мин в день"), tr("in a few days", "через несколько дней"), 15, 0),
            (tr("Ask someone to help", "Попросить помочь"), tr("a favour", "одолжение"), tr("10 min to ask", "10 мин попросить"), tr("depends on them", "как получится"), 10, 0),
        ]
        let daysLeft = t.deadline.map { $0.timeIntervalSince(now) / 86400 } ?? 14
        var best = 0
        var why: String
        if daysLeft < 1.5 {
            // urgent: whatever finishes soonest
            best = raw.firstIndex { $0.3.lowercased().contains(tr("today", "сегодня")) || $0.3.lowercased().contains(tr("right", "сразу")) } ?? 0
            why = tr("The deadline is very close, so this is the one that gets it done today.",
                     "Срок совсем близко, а так получится закончить уже сегодня.")
        } else {
            // otherwise: the best mix of money and her time
            best = raw.indices.min { raw[$0].5 + Double(raw[$0].4) * 0.13 < raw[$1].5 + Double(raw[$1].4) * 0.13 } ?? 0
            why = tr("You have time until the deadline, and this one costs the least of your money and time together.",
                     "До срока ещё есть время, а этот способ дешевле всего по деньгам и времени вместе.")
        }
        if k == nil { why = tr("A general plan. Write a bit more about the thing for better ideas.", "Общий план. Напиши о деле подробнее — идеи будут точнее.") }
        raw = raw.enumerated().sorted { a, b in a.offset == best ? true : (b.offset == best ? false : a.offset < b.offset) }.map { $0.element }
        let opts = raw.enumerated().map { i, w in WayOption(name: w.0, cost: w.1, time: w.2, doneBy: w.3, best: i == 0) }
        return (opts, why)
    }

    /// Pick from her own ratings (1–5 per thing that matters).
    static func decide(_ d: Decision) -> AIDecision? {
        guard d.options.count >= 2, !d.chosen.isEmpty else { return nil }
        var totals: [String: Double] = [:]
        for o in d.options {
            var sum = 0.0, n = 0.0
            for c in d.chosen { sum += Double(d.ratings[c]?[o] ?? 3); n += 1 }
            totals[o] = n > 0 ? sum / n : 3
        }
        let ranked = d.options.sorted { (totals[$0] ?? 0) > (totals[$1] ?? 0) }
        guard let pick = ranked.first else { return nil }
        let wins = d.chosen.filter { c in
            let mine = d.ratings[c]?[pick] ?? 3
            return d.options.allSatisfy { (d.ratings[c]?[$0] ?? 3) <= mine }
        }
        var why = wins.isEmpty ? tr("It's the most balanced across what matters to you.", "Он самый сбалансированный по тому, что для тебя важно.")
            : tr("It's best for ", "Лучше всего по пунктам: ") + wins.joined(separator: ", ").lowercased() + "."
        if ranked.count > 1 {
            let gap = (totals[pick] ?? 0) - (totals[ranked[1]] ?? 0)
            why += gap < 0.4 ? tr(" \(ranked[1]) is very close, so either is fine.", " \(ranked[1]) почти так же хорош — можно любой.")
                             : tr(" \(ranked[1]) comes second.", " На втором месте — \(ranked[1]).")
        }
        var scores: [String: Int] = [:]
        for o in d.options { scores[o] = Int(((totals[o] ?? 3) * 2).rounded()) }
        return AIDecision(pick: pick, why: why, scores: scores)
    }
}
