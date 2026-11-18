import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// Answers from Apple's on-phone AI, when this iPhone has it.
struct AIThing {
    var title: String
    var minutes: Int
    var firstStep: String
    var place: String
    var tip: String
}

struct AIDecision {
    var pick: String
    var why: String
    var scores: [String: Int]
}

#if canImport(FoundationModels)
@available(iOS 26.0, *)
@Generable
struct GThing {
    @Guide(description: "Short title of the task in the user's language, 1 to 5 words, first letter capital, no dates or times")
    var title: String
    @Guide(description: "Realistic number of minutes the task itself takes")
    var minutes: Int
    @Guide(description: "The first concrete step to start, 2 to 5 words, lowercase")
    var firstStep: String
    @Guide(description: "The place, shop or street if the text names one, otherwise an empty string")
    var place: String
    @Guide(description: "A very short tip on the easiest way to do it, at most 6 words, lowercase")
    var tip: String
}

@available(iOS 26.0, *)
@Generable
struct GWay {
    @Guide(description: "Name of this way of doing it, 2 to 4 words, first letter capital")
    var name: String
    @Guide(description: "Estimated money cost in euros, like €12 or €0, may add a few words")
    var cost: String
    @Guide(description: "How much of the user's own time it takes, like 15 min or 1 h 30")
    var time: String
    @Guide(description: "When it would be done, like today, Thu, in 2 days")
    var doneBy: String
}

@available(iOS 26.0, *)
@Generable
struct GWays {
    @Guide(description: "Exactly three different ways to do the task, cheapest, fastest and most effective")
    var options: [GWay]
    @Guide(description: "Index from 0 to 2 of the best way for this user right now")
    var best: Int
    @Guide(description: "One sentence: why that way is best, mentioning time and money")
    var why: String
}

@available(iOS 26.0, *)
@Generable
struct GScore {
    @Guide(description: "The option, written exactly as given")
    var option: String
    @Guide(description: "Score from 1 to 10")
    var score: Int
}

@available(iOS 26.0, *)
@Generable
struct GDecision {
    @Guide(description: "The option you recommend, written exactly as given")
    var pick: String
    @Guide(description: "Two short sentences explaining why, comparing it to the others")
    var why: String
    @Guide(description: "A score for every option")
    var scores: [GScore]
}
#endif

enum AI {
    static var available: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            if case .available = SystemLanguageModel.default.availability { return true }
        }
        #endif
        return false
    }

    static var status: String {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *) {
            switch SystemLanguageModel.default.availability {
            case .available: return tr("On", "Включён")
            case .unavailable(.appleIntelligenceNotEnabled): return tr("Turn on Apple Intelligence in Settings", "Включите Apple Intelligence в Настройках")
            case .unavailable(.modelNotReady): return tr("Downloading…", "Загружается…")
            default: return tr("Not on this iPhone", "Нет на этом iPhone")
            }
        }
        #endif
        return tr("Needs iOS 26", "Нужна iOS 26")
    }

    static var langLine: String { Lang.isRU ? "Answer in Russian." : "Answer in English." }

    static func understand(_ text: String, now: Date) async -> AIThing? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), available {
            let session = LanguageModelSession()
            let prompt = """
            You help a student plan her day. Today is \(Fmt.shortDay(now)). \(langLine)
            She wrote this thing to do: "\(text)"
            Work out a short title, how long it takes, the first step, the place if any, and a tip.
            """
            do {
                let r = try await session.respond(to: prompt, generating: GThing.self)
                let g = r.content
                return AIThing(title: g.title, minutes: max(5, min(g.minutes, 600)), firstStep: g.firstStep, place: g.place, tip: g.tip)
            } catch { return nil }
        }
        #endif
        return nil
    }

    static func ways(for t: Thing, now: Date) async -> ([WayOption], String)? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), available {
            let session = LanguageModelSession()
            var context = "Task: \(t.title)."
            if let d = t.deadline { context += " Deadline: \(Fmt.shortDay(d))." }
            if let s = t.start { context += " Planned: \(Fmt.when(s))." }
            if let p = t.place { context += " Place: \(p)." }
            let prompt = """
            You help a student in Ljubljana, Slovenia decide the best way to get something done. Prices in euros. \(langLine)
            Today is \(Fmt.shortDay(now)). \(context) Original note: "\(t.raw)"
            Give three realistic ways to do it, comparing what's good, cheaper, more effective and less time consuming.
            """
            do {
                let g = try await session.respond(to: prompt, generating: GWays.self).content
                let opts = g.options.prefix(3).enumerated().map { i, o in
                    WayOption(name: o.name, cost: o.cost, time: o.time, doneBy: o.doneBy, best: i == g.best)
                }
                return (Array(opts), g.why)
            } catch { return nil }
        }
        #endif
        return nil
    }

    static func decide(_ d: Decision) async -> AIDecision? {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, *), available {
            let session = LanguageModelSession()
            let prompt = """
            Help a student in Slovenia make a decision. \(langLine)
            Question: \(d.question)
            Options: \(d.options.joined(separator: "; "))
            What matters to her: \(d.chosen.joined(separator: ", "))
            Pick the best option, explain briefly, and score every option.
            """
            do {
                let g = try await session.respond(to: prompt, generating: GDecision.self).content
                var scores: [String: Int] = [:]
                for s in g.scores {
                    let key = d.options.first { $0.caseInsensitiveCompare(s.option) == .orderedSame } ?? s.option
                    scores[key] = max(1, min(10, s.score))
                }
                let pick = d.options.first { $0.caseInsensitiveCompare(g.pick) == .orderedSame } ?? g.pick
                return AIDecision(pick: pick, why: g.why, scores: scores)
            } catch { return nil }
        }
        #endif
        return nil
    }
}
