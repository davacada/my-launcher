import SwiftUI

@MainActor
final class Store: ObservableObject {
    static let shared = Store()

    @Published var things: [Thing] = [] { didSet { save() } }
    @Published var decision = Decision() { didSet { save() } }
    var demo = false

    private struct Saved: Codable { var things: [Thing]; var decision: Decision }

    let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    var attachDir: URL {
        let d = docs.appendingPathComponent("attachments", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }
    private var file: URL { docs.appendingPathComponent("podruga.json") }
    private var loading = false

    init() { load() }

    func load() {
        loading = true
        defer { loading = false }
        guard let data = try? Data(contentsOf: file),
              let s = try? JSONDecoder().decode(Saved.self, from: data) else { return }
        things = s.things
        decision = s.decision
    }

    func save() {
        if loading || demo { return }
        if let data = try? JSONEncoder().encode(Saved(things: things, decision: decision)) {
            try? data.write(to: file, options: .atomic)
        }
    }

    func thing(_ id: UUID) -> Thing? { things.first { $0.id == id } }

    func upsert(_ t: Thing) {
        if let i = things.firstIndex(where: { $0.id == t.id }) { things[i] = t } else { things.append(t) }
    }

    func delete(_ t: Thing) {
        for a in t.attachments { if let f = a.file { try? FileManager.default.removeItem(at: attachDir.appendingPathComponent(f)) } }
        if let e = t.eventID { Cal.shared.remove(eventID: e) }
        things.removeAll { $0.id == t.id }
    }

    func url(_ a: Attachment) -> URL? { a.file.map { attachDir.appendingPathComponent($0) } }

    func things(on day: Date) -> [Thing] {
        let cal = Calendar.current
        return things.filter { t in
            guard let s = t.start else { return false }
            return cal.isDate(s, inSameDayAs: day)
        }.sorted { ($0.start ?? .distantFuture) < ($1.start ?? .distantFuture) }
    }

    /// Copy a file into the app and return the attachment.
    func importFile(_ src: URL, kind: Attachment.Kind? = nil) -> Attachment? {
        let ext = src.pathExtension.lowercased()
        let name = UUID().uuidString + (ext.isEmpty ? "" : "." + ext)
        let dst = attachDir.appendingPathComponent(name)
        let scoped = src.startAccessingSecurityScopedResource()
        defer { if scoped { src.stopAccessingSecurityScopedResource() } }
        do { try FileManager.default.copyItem(at: src, to: dst) } catch { return nil }
        let size = (try? FileManager.default.attributesOfItem(atPath: dst.path)[.size] as? Int64) ?? 0
        let k: Attachment.Kind = kind ?? (["jpg", "jpeg", "png", "heic", "gif", "webp"].contains(ext) ? .photo
            : (["mov", "mp4", "m4v"].contains(ext) ? .video : (["m4a", "caf", "wav", "mp3"].contains(ext) ? .voice : .file)))
        return Attachment(kind: k, file: name, title: src.lastPathComponent, size: size)
    }

    func saveData(_ data: Data, ext: String, kind: Attachment.Kind, title: String) -> Attachment? {
        let name = UUID().uuidString + "." + ext
        let dst = attachDir.appendingPathComponent(name)
        do { try data.write(to: dst) } catch { return nil }
        return Attachment(kind: kind, file: name, title: title, size: Int64(data.count))
    }
}

/// Settings kept on the phone.
enum Pref {
    static let d = UserDefaults.standard
    static func bool(_ k: String, _ def: Bool) -> Bool { d.object(forKey: k) == nil ? def : d.bool(forKey: k) }
    static func int(_ k: String, _ def: Int) -> Int { d.object(forKey: k) == nil ? def : d.integer(forKey: k) }
    static func double(_ k: String, _ def: Double) -> Double { d.object(forKey: k) == nil ? def : d.double(forKey: k) }

    static var remindOffsets: [Int] {
        get { (d.string(forKey: "remind") ?? "10,1440").split(separator: ",").compactMap { Int($0) }.sorted() }
        set { d.set(newValue.sorted().map(String.init).joined(separator: ","), forKey: "remind") }
    }
    static var sleepFrom: Int { int("sleepFrom", 23 * 60 + 30) }
    static var sleepTo: Int { int("sleepTo", 7 * 60 + 30) }
    static var sleepBusy: Bool { bool("sleepBusy", true) }
    static var travelBusy: Bool { bool("travelBusy", true) }
    static var busFare: Double { double("busFare", 1.30) }
    static var fuelPrice: Double { double("fuelPrice", 1.50) }
    static var consumption: Double { double("consumption", 6.5) }
    static var parkingHour: Double { double("parkingHour", 1.30) }
}
