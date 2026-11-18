import Foundation

struct Attachment: Codable, Identifiable, Hashable {
    enum Kind: String, Codable { case photo, video, file, link, voice }
    var id = UUID()
    var kind: Kind
    var file: String?      // name inside the attachments folder
    var url: String?       // for links
    var title: String
    var size: Int64 = 0
    var seconds: Double = 0
}

struct WayOption: Codable, Identifiable, Hashable {
    var id = UUID()
    var name: String
    var cost: String
    var time: String
    var doneBy: String
    var best: Bool
}

struct Thing: Codable, Identifiable, Hashable {
    var id = UUID()
    var title: String
    var raw: String = ""
    var created = Date()
    var deadline: Date?
    var start: Date?
    var minutes: Int = 30
    var place: String?
    var lat: Double?
    var lon: Double?
    var remind: [Int]?
    var firstStep: String?
    var note: String?
    var ways: [WayOption] = []
    var waysWhy: String?
    var attachments: [Attachment] = []
    var eventID: String?
    var done = false
    var travelMinutes: Int?
    var travelNote: String?

    var end: Date? { start.map { $0.addingTimeInterval(Double(minutes) * 60) } }
}

struct Decision: Codable, Identifiable, Hashable {
    var id = UUID()
    var question: String = ""
    var options: [String] = []
    var criteria: [String] = []
    var chosen: [String] = []
    var pick: String?
    var why: String?
    var scores: [String: Int] = [:]
    var ratings: [String: [String: Int]] = [:]   // what matters → option → 1…5
}
