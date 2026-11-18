import AVFoundation
import ImageIO
import LinkPresentation
import MapKit
import PhotosUI
import QuickLook
import SwiftUI
import UniformTypeIdentifiers

struct ThingView: View {
    let id: UUID
    let back: String
    @EnvironmentObject var store: Store
    @EnvironmentObject var router: Router
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    @State private var photos: [PhotosPickerItem] = []
    @State private var importing = false
    @State private var addingLink = false
    @State private var linkText = ""
    @State private var recording = false
    @State private var preview: URL?
    @State private var editing = false

    var thing: Thing? { store.thing(id) }

    var body: some View {
        if let t = thing {
            content(t)
        } else {
            Color.clear.onAppear { dismiss() }
        }
    }

    func sub(_ t: Thing) -> String {
        var parts: [String] = []
        if let s = t.start { parts.append(Fmt.when(s)) } else if let d = t.deadline { parts.append(tr("before ", "до ") + Fmt.dayMonth(d)) }
        else { parts.append(tr("Not planned yet", "Ещё не запланировано")) }
        if let p = t.place { parts.append(p) }
        return parts.joined(separator: " · ")
    }

    func content(_ t: Thing) -> some View {
        ScrollView(showsIndicators: false) {
            VStack(alignment: .leading, spacing: 0) {
                Header(back: back, title: t.title, sub: sub(t))

                if !t.attachments.isEmpty {
                    SectionLabel(text: tr("Attached", "Вложения"))
                    attachments(t)
                }

                HStack(spacing: 8) {
                    PhotosPicker(selection: $photos, maxSelectionCount: 10, matching: .any(of: [.images, .videos])) {
                        AddTile(icon: "photo", title: tr("Photo", "Фото"))
                    }
                    Button { importing = true } label: { AddTile(icon: "file", title: tr("File", "Файл")) }
                    Button { linkText = ""; addingLink = true } label: { AddTile(icon: "link", title: tr("Link", "Ссылка")) }
                    Button { recording = true } label: { AddTile(icon: "mic", title: tr("Voice", "Голос")) }
                }
                .buttonStyle(.plain)
                .padding(.top, 16)
                Text(tr("Any file works: photos, videos, PDFs, documents, screenshots. You can also share into \(appName) from other apps.",
                        "Подойдёт любой файл: фото, видео, PDF, документы, скриншоты. Можно и поделиться в \(appName) из других приложений."))
                    .font(.inter(12.5)).foregroundStyle(C.sub).lineSpacing(3).padding(.top, 12).padding(.horizontal, 4)

                SectionLabel(text: tr("Plan", "План"))
                VStack(spacing: 0) {
                    let rows = planRows(t)
                    ForEach(Array(rows.enumerated()), id: \.offset) { i, r in
                        InfoRow(icon: r.0, label: r.1, value: r.2, last: i == rows.count - 1)
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 4).card()

                VStack(spacing: 8) {
                    NavCard(icon: "spark", title: tr("Ways to do it", "Как это сделать"),
                            sub: tr("cheaper · faster · better", "дешевле · быстрее · лучше")) { router.push(.bestway(t.id)) }
                    if t.place != nil {
                        NavCard(icon: "pin", title: tr("How to get there", "Как добраться"),
                                sub: t.travelNote ?? tr("bus, bike, car, taxi, walk", "автобус, велосипед, машина, такси, пешком")) { router.push(.getthere(t.id)) }
                    }
                }
                .padding(.top, 12)

                HStack(spacing: 10) {
                    PillButton(title: tr("Change time", "Другое время"), soft: true) { editing = true }
                    PillButton(title: t.done ? tr("Not done", "Не готово") : tr("Done ✓", "Готово ✓"), dark: true) {
                        var x = t; x.done.toggle(); store.upsert(x)
                        Task { await Notifier.shared.reschedule() }
                        if x.done { dismiss() }
                    }
                }
                .padding(.top, 18)
                Button(role: .destructive) { store.delete(t); Task { await Notifier.shared.reschedule() }; dismiss() } label: {
                    Text(tr("Delete", "Удалить")).font(.inter(13.5, .semibold)).foregroundStyle(C.pink)
                        .frame(maxWidth: .infinity).padding(.vertical, 14)
                }.buttonStyle(.plain)
            }
            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 60)
        }
        .screen("Подруга: photos, files, links, voice")
        .onChange(of: photos) { _, items in loadPhotos(items) }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { r in
            if case .success(let urls) = r {
                var x = t
                for u in urls { if let a = store.importFile(u) { x.attachments.append(a) } }
                store.upsert(x)
            }
        }
        .alert(tr("Add a link", "Добавить ссылку"), isPresented: $addingLink) {
            TextField("https://", text: $linkText).textInputAutocapitalization(.never).keyboardType(.URL)
            Button(tr("Add", "Добавить")) { addLink(linkText) }
            Button(tr("Cancel", "Отмена"), role: .cancel) {}
        }
        .sheet(isPresented: $recording) { VoiceNoteSheet(thingID: id) }
        .sheet(isPresented: $editing) { TimeSheet(thingID: id) }
        .quickLookPreview($preview)
    }

    func planRows(_ t: Thing) -> [(String, String, String)] {
        var rows: [(String, String, String)] = []
        if let d = t.deadline { rows.append(("cal", tr("Deadline", "Срок"), tr("before ", "до ") + Fmt.dayMonth(d))) }
        if let s = t.start { rows.append(("clock", tr("When", "Когда"), Fmt.when(s))) }
        rows.append(("clock", tr("Takes", "Займёт"), tr("about ", "около ") + Fmt.dur(t.minutes)))
        rows.append(("bell", tr("Remind", "Напомнить"), (t.remind ?? Pref.remindOffsets).map { Fmt.remind($0).lowercased() }.joined(separator: ", ")))
        if let f = t.firstStep { rows.append(("spark", tr("First step", "Первый шаг"), f)) }
        return rows
    }

    // MARK: attachments

    @ViewBuilder
    func attachments(_ t: Thing) -> some View {
        let media = t.attachments.filter { $0.kind == .photo || $0.kind == .video }
        let others = t.attachments.filter { $0.kind != .photo && $0.kind != .video }
        VStack(spacing: 8) {
            if !media.isEmpty {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3), spacing: 8) {
                    ForEach(Array(media.enumerated()), id: \.element.id) { i, a in
                        Button { preview = store.url(a) } label: { MediaTile(a: a, index: i) }
                            .buttonStyle(.plain)
                            .contextMenu { remove(a, from: t) }
                    }
                }
            }
            ForEach(others) { a in
                Group {
                    switch a.kind {
                    case .link: Button { if let u = a.url.flatMap(URL.init(string:)) { openURL(u) } } label: { LinkRow(a: a) }
                    case .voice: VoiceRow(a: a)
                    default: Button { preview = store.url(a) } label: { FileRow(a: a) }
                    }
                }
                .buttonStyle(.plain)
                .contextMenu { remove(a, from: t) }
            }
        }
    }

    func remove(_ a: Attachment, from t: Thing) -> some View {
        Button(role: .destructive) {
            var x = t
            x.attachments.removeAll { $0.id == a.id }
            if let u = store.url(a) { try? FileManager.default.removeItem(at: u) }
            store.upsert(x)
        } label: { Label(tr("Remove", "Удалить"), systemImage: "trash") }
    }

    func loadPhotos(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        Task {
            var added: [Attachment] = []
            for it in items {
                let isVideo = it.supportedContentTypes.contains { $0.conforms(to: .movie) }
                let ext = it.supportedContentTypes.first?.preferredFilenameExtension ?? (isVideo ? "mov" : "jpg")
                if let data = try? await it.loadTransferable(type: Data.self),
                   let a = store.saveData(data, ext: ext, kind: isVideo ? .video : .photo, title: "photo.\(ext)") {
                    added.append(a)
                }
            }
            if var x = store.thing(id) { x.attachments += added; store.upsert(x) }
            photos = []
        }
    }

    func addLink(_ s: String) {
        var str = s.trimmingCharacters(in: .whitespaces)
        if !str.contains("://") { str = "https://" + str }
        guard let u = URL(string: str), u.host != nil else { return }
        var a = Attachment(kind: .link, url: u.absoluteString, title: u.host ?? str)
        if var x = store.thing(id) { x.attachments.append(a); store.upsert(x) }
        Task {
            let md = try? await LPMetadataProvider().startFetchingMetadata(for: u)
            if let title = md?.title, var x = store.thing(id), let i = x.attachments.firstIndex(where: { $0.id == a.id }) {
                a.title = title
                x.attachments[i] = a
                store.upsert(x)
            }
        }
    }
}

struct AddTile: View {
    let icon: String
    let title: String
    var body: some View {
        VStack(spacing: 4) {
            Ic(name: icon, size: 20, color: C.pink)
            Text(title).font(.inter(11.5, .semibold)).foregroundStyle(C.text)
        }
        .frame(maxWidth: .infinity).frame(height: 64)
        .card(18)
    }
}

struct NavCard: View {
    let icon: String
    let title: String
    let sub: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                ZStack {
                    RoundedRectangle(cornerRadius: 12, style: .continuous).fill(C.grad).frame(width: 40, height: 40)
                    Ic(name: icon, size: 21)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text(title).font(.inter(15, .bold)).foregroundStyle(C.text)
                    Text(sub).font(.inter(12)).foregroundStyle(C.sub).lineLimit(1)
                }
                Spacer()
                Ic(name: "chev", size: 16, color: C.sub)
            }
            .padding(.horizontal, 14).padding(.vertical, 13)
            .card(18)
        }.buttonStyle(.plain)
    }
}

let tileGradients: [LinearGradient] = [
    LinearGradient(colors: [Color(hex: 0xFFC2DA), Color(hex: 0xFF8FC0)], startPoint: .topLeading, endPoint: .bottomTrailing),
    LinearGradient(colors: [Color(hex: 0xBFE9FF), Color(hex: 0x8CC8FF)], startPoint: .topLeading, endPoint: .bottomTrailing),
    LinearGradient(colors: [Color(hex: 0xFFE3A0), Color(hex: 0xFFB089)], startPoint: .topLeading, endPoint: .bottomTrailing),
]

final class Thumbs {
    static let cache = NSCache<NSString, UIImage>()
    static func image(_ url: URL, video: Bool) -> UIImage? {
        if let i = cache.object(forKey: url.path as NSString) { return i }
        var img: UIImage?
        if video {
            let g = AVAssetImageGenerator(asset: AVURLAsset(url: url))
            g.appliesPreferredTrackTransform = true
            g.maximumSize = CGSize(width: 400, height: 400)
            if let cg = try? g.copyCGImage(at: .zero, actualTime: nil) { img = UIImage(cgImage: cg) }
        } else if let src = CGImageSourceCreateWithURL(url as CFURL, nil) {
            let opts: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 400,
                                         kCGImageSourceCreateThumbnailWithTransform: true]
            if let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) { img = UIImage(cgImage: cg) }
        }
        if let img { cache.setObject(img, forKey: url.path as NSString) }
        return img
    }
}

struct MediaTile: View {
    let a: Attachment
    let index: Int
    @EnvironmentObject var store: Store
    @State private var img: UIImage?
    var body: some View {
        RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(tileGradients[index % 3])
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                if let img { Image(uiImage: img).resizable().scaledToFill() }
            }
            .overlay(alignment: .bottomLeading) {
                if a.kind == .video { Ic(name: "play", size: 16, color: .white).padding(8) }
            }
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .task {
                guard let u = store.url(a) else { return }
                let video = a.kind == .video
                img = await Task.detached { Thumbs.image(u, video: video) }.value
            }
    }
}

struct FileRow: View {
    let a: Attachment
    var body: some View {
        HStack(spacing: 12) {
            Text((a.title as NSString).pathExtension.uppercased().prefix(4))
                .font(.inter(10, .heavy)).foregroundStyle(C.pink)
                .frame(width: 40, height: 46)
                .background(RoundedRectangle(cornerRadius: 8).fill(C.soft))
            VStack(alignment: .leading, spacing: 1) {
                Text(a.title).font(.inter(14.5, .semibold)).foregroundStyle(C.text).lineLimit(1)
                Text(ByteCountFormatter.string(fromByteCount: a.size, countStyle: .file)).font(.inter(12)).foregroundStyle(C.sub)
            }
            Spacer()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white))
    }
}

struct LinkRow: View {
    let a: Attachment
    var body: some View {
        HStack(spacing: 12) {
            Ic(name: "link", size: 20, color: Color(hex: 0x2E8B57))
                .frame(width: 40, height: 40)
                .background(RoundedRectangle(cornerRadius: 10).fill(C.mint))
            VStack(alignment: .leading, spacing: 1) {
                Text(a.title).font(.inter(14.5, .semibold)).foregroundStyle(C.text).lineLimit(1)
                Text(a.url.flatMap { URL(string: $0)?.host } ?? "").font(.inter(12)).foregroundStyle(C.sub).lineLimit(1)
            }
            Spacer()
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white))
    }
}

struct VoiceRow: View {
    let a: Attachment
    @ObservedObject var player = Player.shared
    let bars: [CGFloat] = [8, 14, 22, 12, 26, 18, 10, 20, 28, 16, 9, 22, 14, 24, 12, 18, 8, 20, 26, 10, 16, 12, 22, 8]
    var body: some View {
        HStack(spacing: 12) {
            Button { player.toggle(a) } label: {
                ZStack {
                    Circle().fill(C.grad).frame(width: 40, height: 40)
                    Ic(name: player.playing == a.id ? "stop" : "play", size: 14)
                }
            }.buttonStyle(.plain)
            HStack(alignment: .center, spacing: 2) {
                ForEach(Array(bars.enumerated()), id: \.offset) { i, h in
                    RoundedRectangle(cornerRadius: 2).fill(i < 14 ? C.pink : C.text.opacity(0.18)).frame(height: h)
                }
            }
            .frame(height: 28)
            Text(String(format: "%d:%02d", Int(a.seconds) / 60, Int(a.seconds) % 60)).font(.inter(12)).foregroundStyle(C.sub)
        }
        .padding(12)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Color.white))
    }
}

struct VoiceNoteSheet: View {
    let thingID: UUID
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @StateObject private var rec = VoiceRec()
    var body: some View {
        VStack(spacing: 20) {
            Text(tr("Voice note", "Голосовая заметка")).font(.inter(17, .bold)).foregroundStyle(C.text)
            Text(rec.text.isEmpty ? "…" : rec.text).font(.inter(15)).foregroundStyle(C.sub).multilineTextAlignment(.center).lineLimit(4)
            HStack(spacing: 3) {
                ForEach(Array(rec.levels.enumerated()), id: \.offset) { _, h in Capsule().fill(C.pink).frame(width: 4, height: h) }
            }.frame(height: 34)
            Button {
                rec.stop()
                if let u = rec.fileURL, var x = store.thing(thingID) {
                    x.attachments.append(Attachment(kind: .voice, file: u.lastPathComponent, title: rec.text.isEmpty ? tr("Voice note", "Голосовая заметка") : rec.text,
                                                    seconds: audioSeconds(u)))
                    store.upsert(x)
                }
                dismiss()
            } label: {
                ZStack {
                    Circle().fill(C.grad).frame(width: 80, height: 80)
                    RoundedRectangle(cornerRadius: 7).fill(C.text).frame(width: 26, height: 26)
                }
            }.buttonStyle(.plain)
            Text(String(format: "%d:%02d", rec.seconds / 60, rec.seconds % 60)).font(.inter(13)).foregroundStyle(C.sub)
        }
        .padding(24)
        .presentationDetents([.height(360)])
        .task { await rec.start() }
        .onDisappear { rec.stop() }
    }
}

struct TimeSheet: View {
    let thingID: UUID
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss
    @State private var date = Date()
    @State private var minutes = 30
    var body: some View {
        NavigationStack {
            Form {
                DatePicker(tr("Start", "Начало"), selection: $date, displayedComponents: [.date, .hourAndMinute])
                    .environment(\.locale, Lang.locale)
                Stepper(tr("Takes ", "Займёт ") + Fmt.dur(minutes), value: $minutes, in: 5...600, step: 5)
                Button(tr("No time — just keep it", "Без времени")) { save(nil) }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(tr("Cancel", "Отмена")) { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button(tr("Save", "Сохранить")) { save(date) } }
            }
        }
        .onAppear {
            if let t = store.thing(thingID) {
                date = t.start ?? Date().addingTimeInterval(3600)
                minutes = t.minutes
            }
        }
        .presentationDetents([.medium])
    }
    func save(_ d: Date?) {
        guard var t = store.thing(thingID) else { return }
        t.start = d
        t.minutes = minutes
        if d == nil, let e = t.eventID { Cal.shared.remove(eventID: e); t.eventID = nil }
        Task { await Planner.commit(t) }
        dismiss()
    }
}
