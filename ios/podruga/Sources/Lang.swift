import SwiftUI

enum Lang {
    static var setting: String { UserDefaults.standard.string(forKey: "lang") ?? "system" }
    static var isRU: Bool {
        let s = setting
        if s == "system" { return Locale.preferredLanguages.first?.hasPrefix("ru") == true }
        return s == "ru"
    }
    static var locale: Locale { Locale(identifier: isRU ? "ru_RU" : "en_GB") }
    static var speech: Locale { Locale(identifier: isRU ? "ru-RU" : "en-US") }
}

/// English or Russian, by the app's language.
func tr(_ en: String, _ ru: String) -> String { Lang.isRU ? ru : en }

/// Name of the app, written the same in both languages.
let appName = "Подруга"

enum Fmt {
    private static func f(_ pattern: String) -> DateFormatter {
        let d = DateFormatter()
        d.locale = Lang.locale
        d.setLocalizedDateFormatFromTemplate(pattern)
        return d
    }
    private static func fixed(_ format: String) -> DateFormatter {
        let d = DateFormatter()
        d.locale = Lang.locale
        d.dateFormat = format
        return d
    }
    static func cap(_ s: String) -> String { s.prefix(1).uppercased() + s.dropFirst() }

    /// WEDNESDAY 18 NOVEMBER / СРЕДА, 18 НОЯБРЯ
    static func dayHeader(_ d: Date) -> String {
        (Lang.isRU ? fixed("EEEE, d MMMM") : fixed("EEEE d MMMM")).string(from: d).uppercased()
    }
    /// Thu 20 Nov / Чт, 20 нояб
    static func shortDay(_ d: Date) -> String {
        let s = (Lang.isRU ? fixed("EEE, d MMM") : fixed("EEE d MMM")).string(from: d)
        return cap(s.replacingOccurrences(of: ".", with: ""))
    }
    static func dayMonth(_ d: Date) -> String {
        fixed("d MMM").string(from: d).replacingOccurrences(of: ".", with: "")
    }
    static func weekdayShort(_ d: Date) -> String {
        cap(fixed("EEE").string(from: d).replacingOccurrences(of: ".", with: ""))
    }
    static func monthTitle(_ d: Date) -> String { cap(fixed("LLLL").string(from: d)) }
    static func time(_ d: Date) -> String { fixed("HH:mm").string(from: d) }
    static func when(_ d: Date) -> String { shortDay(d) + " · " + time(d) }

    /// 1 h 30 · 45 min / 1 ч 30 · 45 мин
    static func dur(_ minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        let hs = tr("h", "ч"), ms = tr("min", "мин")
        if h == 0 { return "\(m) \(ms)" }
        if m == 0 { return "\(h) \(hs)" }
        return "\(h) \(hs) \(m)"
    }

    static func remind(_ offset: Int) -> String {
        switch offset {
        case 0: return tr("At the time", "В момент")
        case 1440: return tr("1 day before", "За 1 день")
        case 2880: return tr("2 days before", "За 2 дня")
        case 60: return tr("1 h before", "За 1 час")
        default:
            if offset % 1440 == 0 { return tr("\(offset / 1440) days before", "За \(offset / 1440) дн.") }
            return tr("\(offset) min before", "За \(offset) мин")
        }
    }

    static func money(_ v: Double) -> String {
        if v == 0 { return "€0" }
        return String(format: "€%.2f", v)
    }

    static func minuteOfDay(_ m: Int) -> String { String(format: "%02d:%02d", m / 60, m % 60) }
}
