import Foundation

/// What Подруга understood from a sentence, without any AI.
struct Parsed {
    var title: String = ""
    var start: Date?          // a day + time to do it
    var day: Date?            // a day without time
    var deadline: Date?
    var place: String?
    var minutes: Int?
    var links: [URL] = []
    var highlights: [String] = []
}

enum Parser {
    // regex pieces, January first / Sunday first
    static let monthsEN = ["jan(?:uary)?", "feb(?:ruary)?", "mar(?:ch)?", "apr(?:il)?", "may", "june?", "july?", "aug(?:ust)?",
                           "sept?(?:ember)?", "oct(?:ober)?", "nov(?:ember)?", "dec(?:ember)?"]
    static let monthsRU = ["янв(?:ар[ьяе])?", "фев(?:рал[ьяе])?", "мар(?:т[ае]?)?", "апр(?:ел[ьяе])?", "ма[йяе]", "июн[ьяе]?",
                           "июл[ьяе]?", "авг(?:уст[ае]?)?", "сен(?:т(?:ябр[ьяе])?)?", "окт(?:ябр[ьяе])?", "ноя(?:бр[ьяе])?", "дек(?:абр[ьяе])?"]
    static let weekdaysEN = ["sun(?:day)?", "mon(?:day)?", "tue(?:s|sday)?", "wed(?:nesday)?", "thu(?:rs|rsday)?", "fri(?:day)?", "sat(?:urday)?"]
    static let weekdaysRU = ["воскресенье", "понедельник", "вторник", "сред[ау]", "четверг", "пятниц[ау]", "суббот[ау]"]
    static let stems = ["jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec", "mon", "tue", "wed", "thu",
                        "fri", "sat", "sun", "янв", "фев", "мар", "апр", "мая", "май", "июн", "июл", "авг", "сен", "окт", "ноя", "дек",
                        "пон", "вто", "сре", "чет", "пят", "суб", "вос"]

    static func rx(_ p: String) -> NSRegularExpression { try! NSRegularExpression(pattern: p, options: [.caseInsensitive]) }

    static func first(_ pattern: String, in s: String) -> (NSRange, [String])? {
        let ns = s as NSString
        guard let m = rx(pattern).firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        var groups: [String] = []
        for i in 0..<m.numberOfRanges {
            let r = m.range(at: i)
            groups.append(r.location == NSNotFound ? "" : ns.substring(with: r))
        }
        return (m.range, groups)
    }

    static func parse(_ input: String, now: Date = Date()) -> Parsed {
        var p = Parsed()
        let cal = Calendar.current
        var text = input
        var remove: [String] = []
        let today = cal.startOfDay(for: now)

        // links
        if let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue) {
            for m in det.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
                if let u = m.url { p.links.append(u); remove.append((text as NSString).substring(with: m.range)) }
            }
        }

        // is it a deadline ("before", "by", "до", "к")?
        var isDeadline = false
        if first(#"\b(before|by|until|no later than)\b|(^|\s)(до|к|не позже)\s"#, in: text) != nil { isDeadline = true }

        // day
        var day: Date?
        if let m = first(#"\b(day after tomorrow)\b|послезавтра"#, in: text) {
            day = cal.date(byAdding: .day, value: 2, to: today); remove.append(m.1[0])
        } else if let m = first(#"\b(tomorrow)\b|завтра"#, in: text) {
            day = cal.date(byAdding: .day, value: 1, to: today); remove.append(m.1[0])
        } else if let m = first(#"\b(today|tonight)\b|сегодня"#, in: text) {
            day = today; remove.append(m.1[0])
        }
        if day == nil {
            // weekdays
            for (i, w) in weekdaysEN.enumerated() {
                if let m = first(#"\b((next|this|on)\s+)?"# + w + #"\b"#, in: text) {
                    day = nextWeekday(i + 1, from: today); remove.append(m.1[0]); p.highlights.append(m.1[0]); break
                }
            }
            if day == nil {
                for (i, w) in weekdaysRU.enumerated() {
                    if let m = first(#"((в|во|на|в следующ\w*|в эт\w*)\s+)?\b"# + w + #"\b"#, in: text) {
                        day = nextWeekday(i + 1, from: today); remove.append(m.1[0]); p.highlights.append(m.1[0]); break
                    }
                }
            }
        }
        // explicit dates: 10 jan, jan 10, 10.01, 10 января
        if day == nil {
            if let m = first(#"\b(\d{1,2})[./](\d{1,2})(?:[./](\d{2,4}))?\b"#, in: text),
               let d = Int(m.1[1]), let mo = Int(m.1[2]), (1...31).contains(d), (1...12).contains(mo) {
                day = makeDate(day: d, month: mo, year: Int(m.1[3]), now: now); remove.append(m.1[0])
            } else {
                for (i, mo) in monthsEN.enumerated() {
                    if let m = first(#"\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?"# + mo + #"\b"#, in: text) ?? first(#"\b"# + mo + #"\s+(\d{1,2})(?:st|nd|rd|th)?\b"#, in: text) {
                        let num = m.1.dropFirst().first { Int($0) != nil }.flatMap { Int($0) } ?? 1
                        day = makeDate(day: num, month: i + 1, year: nil, now: now); remove.append(m.1[0]); break
                    }
                }
                if day == nil {
                    for (i, mo) in monthsRU.enumerated() {
                        if let m = first(#"(\d{1,2})\s+"# + mo + #"\b"#, in: text), let d = Int(m.1[1]) {
                            day = makeDate(day: d, month: i + 1, year: nil, now: now); remove.append(m.1[0]); break
                        }
                    }
                }
            }
        }
        // month only: "in january", "в январе" → a deadline at the start of the month
        if day == nil {
            for (i, mo) in monthsEN.enumerated() {
                if let m = first(#"\b(in|for)\s+"# + mo + #"\b"#, in: text) {
                    p.deadline = makeDate(day: 1, month: i + 1, year: nil, now: now); remove.append(m.1[0]); break
                }
            }
            if p.deadline == nil {
                for (i, mo) in monthsRU.enumerated() {
                    if let m = first(#"(?:^|\s)(в|на)\s+"# + mo + #"\b"#, in: text) {
                        p.deadline = makeDate(day: 1, month: i + 1, year: nil, now: now); remove.append(m.1[0]); break
                    }
                }
            }
            if p.deadline != nil { isDeadline = true }
        }
        if day == nil, let m = first(#"\bin (\d+) days?\b|через (\d+) (дн|день|дня)"#, in: text) {
            let n = Int(m.1[1]) ?? Int(m.1[2]) ?? 1
            day = cal.date(byAdding: .day, value: n, to: today); remove.append(m.1[0])
        }
        if day == nil, let m = first(#"\bnext week\b|на следующей неделе"#, in: text) {
            day = cal.date(byAdding: .day, value: 7, to: today); isDeadline = true; remove.append(m.1[0])
        }

        // time
        var hour: Int?, minute = 0
        if let m = first(#"\b(\d{1,2}):(\d{2})\b"#, in: text) {
            hour = Int(m.1[1]); minute = Int(m.1[2]) ?? 0; remove.append(m.1[0]); p.highlights.append(m.1[0])
            if let pre = first(#"\b(at|around|about)\s+\d{1,2}:\d{2}|(в|около|к)\s+\d{1,2}:\d{2}"#, in: text) { remove.append(pre.1[0]) }
        } else if let m = first(#"\b(at|around|about|by)\s+(\d{1,2})\s*(am|pm|a\.m\.|p\.m\.)?\b"#, in: text) {
            hour = Int(m.1[2]); let ap = m.1[3].lowercased()
            if ap.hasPrefix("p"), let h = hour, h < 12 { hour = h + 12 }
            if ap.isEmpty, let h = hour, h < 8 { hour = h + 12 }
            remove.append(m.1[0]); p.highlights.append(m.1[0])
        } else if let m = first(#"(?:^|\s)(в|около|к)\s+(\d{1,2})(\s*(утра|дня|вечера|часов|час|ч))?(?=\s|$|,|\.)"#, in: text) {
            hour = Int(m.1[2]); let ap = m.1[4].lowercased()
            if (ap == "вечера" || ap == "дня"), let h = hour, h < 12 { hour = h + 12 }
            if ap.isEmpty || ap.hasPrefix("ч"), let h = hour, h < 8 { hour = h + 12 }
            let whole = m.1[0].trimmingCharacters(in: .whitespaces)
            remove.append(whole); p.highlights.append(whole)
        } else if let m = first(#"\b(\d{1,2})\s*(am|pm)\b"#, in: text) {
            hour = Int(m.1[1]); if m.1[2].lowercased() == "pm", let h = hour, h < 12 { hour = h + 12 }
            remove.append(m.1[0]); p.highlights.append(m.1[0])
        }
        if let h = hour, !(0...23).contains(h) { hour = nil }

        // duration
        if let m = first(#"\b(\d+)\s*(min|mins|minutes|minute)\b|(\d+)\s*(мин|минут)\w*"#, in: text) {
            p.minutes = Int(m.1[1]) ?? Int(m.1[3]); remove.append(m.1[0])
        } else if let m = first(#"\b(\d+(?:[.,]5)?)\s*(h|hr|hrs|hour|hours)\b|(\d+(?:[.,]5)?)\s*(ч|час)\w*"#, in: text) {
            let v = Double((m.1[1].isEmpty ? m.1[3] : m.1[1]).replacingOccurrences(of: ",", with: ".")) ?? 1
            p.minutes = Int(v * 60); remove.append(m.1[0])
        } else if first(#"half an hour|полчаса"#, in: text) != nil { p.minutes = 30 }

        // compose
        if let d = day {
            if let h = hour {
                p.start = cal.date(bySettingHour: h, minute: minute, second: 0, of: d)
            } else if isDeadline {
                p.deadline = cal.date(bySettingHour: 23, minute: 59, second: 0, of: d)
            } else {
                p.day = d
            }
        } else if let h = hour {
            var s = cal.date(bySettingHour: h, minute: minute, second: 0, of: today)!
            if s < now { s = cal.date(byAdding: .day, value: 1, to: s)! }
            p.start = s
        }

        // place: a capitalised name after "on/at/in/на/в/у"
        let monthWords = stems
        let placeRx = rx(#"(?:^|\s)(on|at|in|to|na|на|в|во|у|к)\s+((?:\p{Lu}[\p{L}\-]+)(?:\s+\p{Lu}[\p{L}\-]+){0,2}(?:\s+\d{1,3}[a-z]?)?)"#)
        let ns = text as NSString
        for m in placeRx.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
            let name = ns.substring(with: m.range(at: 2))
            let low = name.lowercased()
            if monthWords.contains(where: { low.hasPrefix($0) }) { continue }
            p.place = name
            p.highlights.append(name)
            remove.append(ns.substring(with: m.range).trimmingCharacters(in: .whitespaces))
            break
        }
        if p.place == nil, let det = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.address.rawValue),
           let m = det.firstMatch(in: text, range: NSRange(location: 0, length: ns.length)) {
            p.place = ns.substring(with: m.range); remove.append(p.place!); p.highlights.append(p.place!)
        }

        // title: the sentence without the bits above, up to the first comma / "after" / "before"
        for r in remove.sorted(by: { $0.count > $1.count }) {
            if let range = text.range(of: r, options: .caseInsensitive) { text.replaceSubrange(range, with: " ") }
        }
        let cuts = [",", ";", " after ", " before ", " by ", " until ", " после ", " до ", " the one ", " который ", " которая "]
        var t = " " + text + " "
        for c in cuts {
            if let r = t.range(of: c, options: .caseInsensitive), t.distance(from: t.startIndex, to: r.lowerBound) > 2 {
                t = String(t[..<r.lowerBound])
            }
        }
        t = t.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: CharacterSet.whitespaces.union(.punctuationCharacters))
        for filler in ["remind me to ", "remind me ", "i need to ", "i have to ", "need to ", "напомни ", "мне нужно ", "нужно ", "надо "] {
            if t.lowercased().hasPrefix(filler) { t = String(t.dropFirst(filler.count)) }
        }
        if t.count > 70 { t = String(t.prefix(70)) + "…" }
        p.title = Fmt.cap(t.isEmpty ? input.trimmingCharacters(in: .whitespacesAndNewlines) : t)
        return p
    }

    static func nextWeekday(_ weekday: Int, from today: Date) -> Date {
        let cal = Calendar.current
        var d = cal.date(byAdding: .day, value: 1, to: today)!
        while cal.component(.weekday, from: d) != weekday { d = cal.date(byAdding: .day, value: 1, to: d)! }
        return d
    }

    static func makeDate(day: Int, month: Int, year: Int?, now: Date) -> Date? {
        let cal = Calendar.current
        var y = year ?? cal.component(.year, from: now)
        if y < 100 { y += 2000 }
        var c = DateComponents(year: y, month: month, day: day)
        guard var d = cal.date(from: c) else { return nil }
        if year == nil, d < cal.startOfDay(for: now) { c.year = y + 1; d = cal.date(from: c) ?? d }
        return d
    }
}
