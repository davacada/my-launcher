import AVFoundation
import Speech
import SwiftUI

/// Records her voice to a file and writes down what she says.
@MainActor
final class VoiceRec: ObservableObject {
    @Published var text = ""
    @Published var levels: [CGFloat] = Array(repeating: 6, count: 17)
    @Published var running = false
    @Published var seconds = 0
    @Published var problem: String?

    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var recognizer: SFSpeechRecognizer?
    private var timer: Timer?
    private(set) var fileURL: URL?
    var demo = false

    static func askSpeech() async -> Bool {
        await withCheckedContinuation { c in
            SFSpeechRecognizer.requestAuthorization { s in c.resume(returning: s == .authorized) }
        }
    }

    func start() async {
        if demo {
            text = tr("dentist next tuesday after uni, the one on Trubarjeva, around 3",
                      "стоматолог во вторник после пар, тот что на Trubarjeva, около 3")
            running = true; seconds = 7
            levels = [6, 12, 20, 28, 16, 24, 32, 18, 10, 22, 30, 14, 8, 18, 26, 12, 6]
            return
        }
        problem = nil
        let mic = await AVAudioApplication.requestRecordPermission()
        let sp = await VoiceRec.askSpeech()
        guard mic else { problem = tr("Allow the microphone in Settings", "Разрешите микрофон в Настройках"); return }
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playAndRecord, mode: .measurement, options: [.duckOthers, .defaultToSpeaker])
            try session.setActive(true, options: .notifyOthersOnDeactivation)
        } catch { problem = error.localizedDescription; return }

        let url = Store.shared.attachDir.appendingPathComponent(UUID().uuidString + ".caf")
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else { problem = tr("No microphone", "Нет микрофона"); return }
        let file = try? AVAudioFile(forWriting: url, settings: format.settings)
        fileURL = url

        var req: SFSpeechAudioBufferRecognitionRequest?
        if sp, let rec = SFSpeechRecognizer(locale: Lang.speech), rec.isAvailable {
            let r = SFSpeechAudioBufferRecognitionRequest()
            r.shouldReportPartialResults = true
            r.addsPunctuation = true
            if rec.supportsOnDeviceRecognition { r.requiresOnDeviceRecognition = true }
            req = r
            recognizer = rec
        } else if !sp {
            problem = tr("Allow speech recognition in Settings to get text", "Разрешите распознавание речи в Настройках")
        }
        request = req

        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buf, _ in
            req?.append(buf)
            try? file?.write(from: buf)
            var level: Float = 0
            if let ch = buf.floatChannelData?[0] {
                let n = Int(buf.frameLength)
                var sum: Float = 0
                for i in stride(from: 0, to: n, by: 4) { sum += ch[i] * ch[i] }
                level = sqrt(sum / Float(max(1, n / 4)))
            }
            let h = CGFloat(min(34, 6 + Double(level) * 260))
            DispatchQueue.main.async {
                guard let self else { return }
                self.levels.removeFirst()
                self.levels.append(h)
            }
        }
        engine.prepare()
        do { try engine.start() } catch { problem = error.localizedDescription; input.removeTap(onBus: 0); return }

        if let req, let rec = recognizer {
            task = rec.recognitionTask(with: req) { [weak self] result, _ in
                guard let result else { return }
                let s = result.bestTranscription.formattedString
                DispatchQueue.main.async { self?.text = s }
            }
        }
        running = true
        seconds = 0
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            DispatchQueue.main.async { self?.seconds += 1 }
        }
    }

    func stop() {
        if demo { running = false; return }
        guard running else { return }
        timer?.invalidate(); timer = nil
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
        request?.endAudio()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in self?.task?.finish() }
        running = false
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    func reset() {
        text = ""; seconds = 0
        levels = Array(repeating: 6, count: 17)
        if let u = fileURL { try? FileManager.default.removeItem(at: u) }
        fileURL = nil
    }
}

/// Plays a voice note.
@MainActor
final class Player: NSObject, ObservableObject, AVAudioPlayerDelegate {
    static let shared = Player()
    @Published var playing: UUID?
    private var p: AVAudioPlayer?

    func toggle(_ a: Attachment) {
        if playing == a.id { p?.stop(); playing = nil; return }
        guard let u = Store.shared.url(a) else { return }
        try? AVAudioSession.sharedInstance().setCategory(.playback)
        try? AVAudioSession.sharedInstance().setActive(true)
        p = try? AVAudioPlayer(contentsOf: u)
        p?.delegate = self
        p?.play()
        playing = a.id
    }

    nonisolated func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        Task { @MainActor in self.playing = nil }
    }
}

func audioSeconds(_ url: URL) -> Double {
    guard let f = try? AVAudioFile(forReading: url) else { return 0 }
    return Double(f.length) / f.processingFormat.sampleRate
}
