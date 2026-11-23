import AVFoundation
import Combine
import Foundation
import UIKit
import WebKit

final class PlaybackController: NSObject, ObservableObject {
    @Published private(set) var currentChannel: Channel?
    @Published private(set) var player: AVPlayer?
    @Published private(set) var status = L10n.t("playback.initial_status")
    @Published private(set) var isPreparing = false
    @Published private(set) var isBuffering = false
    @Published private(set) var hasVisibleVideo = false
    @Published private(set) var usesOfficialPlayer = false
    @Published var errorMessage: String?

    let webView: WKWebView

    private static let bridgeName = "yspBridge"
    private static let cctvWebFallbackEnabled = false
    private static let knownEncryptedPids: Set<String> = [
        "600002493",
        "600002309",
        "600002521"
    ]
    // Match the Android auth WebView so the official page returns the same
    // player payload and HLS streams.
    private static let userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private static let cctvUserAgent =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 26_4 like Mac OS X) " +
        "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.4 " +
        "Mobile/15E148 Safari/604.1"
    private static let cctvPagePaths: [String: String] = [
        "CCTV1": "cctv1",
        "CCTV2": "cctv2",
        "CCTV3": "cctv3",
        "CCTV4": "cctv4",
        "CCTV5": "cctv5",
        "CCTV5+": "cctv5plus",
        "CCTV6": "cctv6",
        "CCTV7": "cctv7",
        "CCTV8": "cctv8",
        "CCTV9": "cctvjilu",
        "CCTV10": "cctv10",
        "CCTV11": "cctv11",
        "CCTV12": "cctv12",
        "CCTV13": "cctv13",
        "CCTV14": "cctvchild",
        "CCTV15": "cctv15",
        "CCTV16-HD": "cctv16",
        "CCTV16(4K)": "cctv16",
        "CCTV17": "cctv17"
    ]

    private enum RenderMode {
        case idle
        case native
        case official
    }

    private var renderMode = RenderMode.idle
    private var generation = 0
    private var pendingChannel: Channel?
    private var authTimeoutTask: Task<Void, Never>?
    private var fallbackRetryTask: Task<Void, Never>?
    private var fallbackRetryAttempts = 0
    private var watchdogTimer: Timer?
    private var watchdogStartedAt = Date()
    private var lastWatchdogActionAt = Date.distantPast
    private var itemStatusObservation: NSKeyValueObservation?
    private var timeControlObservation: NSKeyValueObservation?
    private var periodicTimeObserver: Any?
    private var observedPlayer: AVPlayer?
    private var observers: [NSObjectProtocol] = []
    private var hasRenderedFrame = false
    private var lastObservedTime = CMTime.invalid
    private var lastProgressAt = Date.distantPast
    private var nativeRecoveryAttempts = 0
    private var usedOfficialFallback = false
    private var cctvFallbackUsed = false
    private var playingCctvFallback = false
    private var officialStartedAt = Date()
    private var officialPlaying = false
    private var officialProbeInFlight = false
    private var officialProbeStartedAt = Date.distantPast
    private var officialLastPosition = 0.0
    private var officialVideoId: String?
    private var officialLastProgressAt = Date.distantPast
    private var officialRecoveryAttempts = 0
    private var officialHealthySince: Date?
    private var qualityLadder: [QualityOption] = []
    private var qualityIndex = -1
    private var backupUrls: [URL] = []
    private var backupIndex = 0
    private var playingBackup = false
    private var officialHasVideoFrame = false
    private var isForeground = true
    private var backgroundedAt: Date?

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        let contentController = WKUserContentController()
        configuration.userContentController = contentController
        webView = WKWebView(frame: .zero, configuration: configuration)

        super.init()

        contentController.add(self, name: Self.bridgeName)
        installScripts(into: contentController)
        webView.navigationDelegate = self
        webView.customUserAgent = Self.userAgent
        webView.alpha = 0.01
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.isScrollEnabled = false
        webView.allowsBackForwardNavigationGestures = false

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleItemFailure(_:)),
            name: .AVPlayerItemFailedToPlayToEndTime,
            object: nil
        )
        observers.append(
            NotificationCenter.default.addObserver(
                forName: .AVPlayerItemPlaybackStalled,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                guard let self, self.renderMode == .native else { return }
                self.isBuffering = true
                self.status = L10n.t("status.buffering")
            }
        )
        observers.append(
            NotificationCenter.default.addObserver(
                forName: UIApplication.didEnterBackgroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                self?.isForeground = false
                self?.backgroundedAt = Date()
            }
        )
        observers.append(
            NotificationCenter.default.addObserver(
                forName: UIApplication.willEnterForegroundNotification,
                object: nil,
                queue: .main
            ) { [weak self] _ in
                guard let self else { return }
                self.isForeground = true
                self.resumeAfterForeground()
            }
        )
    }

    deinit {
        authTimeoutTask?.cancel()
        fallbackRetryTask?.cancel()
        watchdogTimer?.invalidate()
        itemStatusObservation?.invalidate()
        timeControlObservation?.invalidate()
        if let periodicTimeObserver, let observedPlayer {
            observedPlayer.removeTimeObserver(periodicTimeObserver)
        }
        NotificationCenter.default.removeObserver(self)
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    func play(_ channel: Channel) {
        beginPlay(channel, resetRecovery: true)
    }

    private func beginPlay(_ channel: Channel, resetRecovery: Bool) {
        guard !channel.pid.isEmpty else {
            errorMessage = L10n.t("error.channel_missing_pid")
            return
        }

        generation &+= 1
        let token = generation
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        fallbackRetryTask?.cancel()
        fallbackRetryTask = nil
        stopWatchdog()
        tearDownNativePlayer()

        currentChannel = channel
        pendingChannel = channel
        renderMode = .idle
        usesOfficialPlayer = false
        playingCctvFallback = false
        backupUrls = []
        backupIndex = 0
        playingBackup = false
        officialHasVideoFrame = false
        isPreparing = true
        isBuffering = false
        hasVisibleVideo = false
        errorMessage = nil
        status = L10n.t("status.auth_loading")
        hasRenderedFrame = false
        lastObservedTime = .invalid
        lastProgressAt = Date()
        officialPlaying = false
        officialProbeInFlight = false
        webView.isHidden = false
        webView.alpha = 0.01
        webView.customUserAgent = Self.userAgent
        configureAudioSession()
        webView.evaluateJavaScript(
            "window.__yspStopBackupPlayer && window.__yspStopBackupPlayer();"
        ) { _, _ in }
        if resetRecovery {
            nativeRecoveryAttempts = 0
            usedOfficialFallback = false
            cctvFallbackUsed = false
            playingCctvFallback = false
            officialRecoveryAttempts = 0
            officialHealthySince = nil
            officialVideoId = nil
            qualityLadder = []
            qualityIndex = -1
        }

        guard let url = URL(string: "https://yangshipin.cn/tv/home?pid=\(channel.pid)") else {
            isPreparing = false
            errorMessage = L10n.t("error.channel_invalid_url")
            return
        }
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        webView.load(request)

        authTimeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard
                    let self,
                    token == self.generation,
                    self.isPreparing,
                    self.renderMode == .idle,
                    self.pendingChannel?.pid == channel.pid
                else {
                    return
                }
                self.failPlayback(
                    title: L10n.t("title.auth_timeout"),
                    message: L10n.t("msg.auth_timeout")
                )
            }
        }
    }

    func reload() {
        if let channel = currentChannel {
            play(channel)
        }
    }

    func stop() {
        generation &+= 1
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        fallbackRetryTask?.cancel()
        fallbackRetryTask = nil
        stopWatchdog()
        tearDownNativePlayer()
        pendingChannel = nil
        renderMode = .idle
        usesOfficialPlayer = false
        playingCctvFallback = false
        backupUrls = []
        backupIndex = 0
        playingBackup = false
        officialHasVideoFrame = false
        isPreparing = false
        isBuffering = false
        hasVisibleVideo = false
        hasRenderedFrame = false
        status = L10n.t("playback.initial_status")
        webView.isHidden = true
        webView.alpha = 0.01
        webView.stopLoading()
        webView.evaluateJavaScript(
            "window.__yspPauseOfficialVideo && window.__yspPauseOfficialVideo();"
        ) { _, _ in }
    }

    private func installScripts(into controller: WKUserContentController) {
        let compatibilityShim = """
        (function () {
          if (!window.YspAndroidBridge) {
            window.YspAndroidBridge = {
              postMessage: function (text) {
                try {
                  window.webkit.messageHandlers.yspBridge.postMessage(String(text));
                } catch (error) {}
              },
              isOfficialPlayerMode: function () { return false; }
            };
          }
        })();
        """
        controller.addUserScript(
            WKUserScript(
                source: compatibilityShim,
                injectionTime: .atDocumentStart,
                forMainFrameOnly: false
            )
        )

        if let url = Bundle.main.url(forResource: "auth_bridge", withExtension: "js"),
           let script = try? String(contentsOf: url, encoding: .utf8) {
            controller.addUserScript(
                WKUserScript(
                    source: script,
                    injectionTime: .atDocumentStart,
                    forMainFrameOnly: false
                )
            )
        }
    }

    private func handleBridgeMessage(_ raw: String) {
        guard
            let data = raw.data(using: .utf8),
            let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let type = object["type"] as? String
        else {
            return
        }

        switch type {
        case "bridge_ready":
            if isPreparing, renderMode == .idle {
                status = L10n.t("status.official_loaded")
            }

        case "live_info":
            guard let channel = pendingChannel else { return }
            guard
                let rawURL = object["playurl"] as? String,
                let url = URL(string: rawURL),
                url.scheme?.lowercased() == "https",
                url.path.lowercased().hasSuffix(".m3u8")
            else {
                failPlayback(
                    title: L10n.t("title.stream_failed"),
                    message: L10n.t("msg.invalid_stream")
                )
                return
            }
            if let livePid = object["livepid"] as? String,
               !livePid.isEmpty,
               livePid != channel.pid {
                return
            }
            backupUrls = backupURLs(in: object, excluding: url)
            backupIndex = 0
            playingBackup = false
            officialHasVideoFrame = false
            officialPlaying = false
            officialRecoveryAttempts = 0
            officialHealthySince = nil
            lastWatchdogActionAt = .distantPast
            let token = generation
            let encryption = encryptionFlag(in: object)
            updateQualityLadder(
                formats: object["formats"] as? [[String: Any]],
                currentDefinition: stringValue(object["defn"])
            )
            pendingChannel = nil
            authTimeoutTask?.cancel()
            authTimeoutTask = nil
            if encryption != nil, encryption != 0 {
                logPlayback(
                    "live_info pid=\(channel.pid) mode=official " +
                    "host=\(url.host ?? "unknown") backups=\(backupUrls.count)"
                )
                beginOfficialPlayback(generation: token, automaticFallback: false)
            } else if Self.knownEncryptedPids.contains(channel.pid) {
                logPlayback(
                    "live_info pid=\(channel.pid) mode=official-known " +
                    "host=\(url.host ?? "unknown") backups=\(backupUrls.count)"
                )
                beginOfficialPlayback(generation: token, automaticFallback: false)
            } else {
                logPlayback(
                    "live_info pid=\(channel.pid) mode=native " +
                    "host=\(url.host ?? "unknown") backups=\(backupUrls.count)"
                )
                startNativePlayback(url: url, generation: token)
            }

        case "playing":
            guard renderMode == .official else { return }
            if let reportedBackup = boolValue(object["backup"]) {
                if playingBackup, !reportedBackup {
                    return
                }
                playingBackup = reportedBackup
            }
            fallbackRetryTask?.cancel()
            fallbackRetryTask = nil
            fallbackRetryAttempts = 0
            officialPlaying = true
            officialLastProgressAt = Date()
            let width = (object["width"] as? NSNumber)?.intValue ?? 0
            let height = (object["height"] as? NSNumber)?.intValue ?? 0
            if width > 0, height > 0 {
                officialHasVideoFrame = true
            }
            logPlayback(
                "playing backup=\(playingBackup) width=\(width) " +
                "height=\(height) hasFrame=\(officialHasVideoFrame)"
            )
            if officialHasVideoFrame {
                hasVisibleVideo = true
                isPreparing = false
                isBuffering = false
                status = playingBackup
                    ? L10n.t("status.playing_backup")
                    : L10n.t("status.playing")
            } else {
                isBuffering = true
                status = playingBackup
                    ? L10n.t("status.backup_started_waiting")
                    : L10n.t("status.video_started_waiting")
            }

        case "buffering":
            guard renderMode == .official else { return }
            if let reportedBackup = boolValue(object["backup"]),
               playingBackup,
               !reportedBackup {
                return
            }
            isBuffering = true
            status = L10n.t("status.buffering")
            logPlayback(
                "buffering backup=\(playingBackup) visible=\(hasVisibleVideo)"
            )

        case "video_size":
            guard renderMode == .official else { return }
            if let reportedBackup = boolValue(object["backup"]),
               playingBackup,
               !reportedBackup {
                return
            }
            let width = (object["width"] as? NSNumber)?.intValue ?? 0
            let height = (object["height"] as? NSNumber)?.intValue ?? 0
            if width > 0, height > 0 {
                officialHasVideoFrame = true
                officialLastProgressAt = Date()
                hasVisibleVideo = true
                isPreparing = false
                isBuffering = false
                status = playingBackup
                    ? L10n.t("status.playing_backup")
                    : L10n.t("status.playing")
            }

        case "video_progress":
            guard renderMode == .official else { return }
            if let reportedBackup = boolValue(object["backup"]),
               playingBackup,
               !reportedBackup {
                return
            }
            let width = (object["width"] as? NSNumber)?.intValue ?? 0
            let height = (object["height"] as? NSNumber)?.intValue ?? 0
            guard width > 0, height > 0 else { return }
            officialHasVideoFrame = true
            officialPlaying = true
            officialLastProgressAt = Date()
            hasVisibleVideo = true
            isPreparing = false
            isBuffering = false
            status = playingBackup
                ? L10n.t("status.playing_backup")
                : L10n.t("status.playing")

        case "playback_error":
            let message = object["message"] as? String ?? L10n.t("msg.official_error")
            if let reportedBackup = boolValue(object["backup"]),
               playingBackup,
               !reportedBackup {
                return
            }
            if renderMode == .official || isPreparing {
                if tryNextBackup(
                    generation: generation,
                    reason: message
                ) {
                    return
                }
                failPlayback(title: L10n.t("title.playback_failed"), message: message)
            }

        case "error":
            let message = object["message"] as? String ?? L10n.t("msg.official_error")
            if isPreparing {
                failPlayback(title: L10n.t("title.stream_failed"), message: message)
            }

        default:
            break
        }
    }

    private func encryptionFlag(in object: [String: Any]) -> Int? {
        func number(_ value: Any?) -> Int? {
            if let value = value as? NSNumber { return value.intValue }
            if let value = value as? String { return Int(value) }
            return nil
        }

        if let direct = number(object["encrypt"]) { return direct }
        guard let formats = object["formats"] as? [[String: Any]] else {
            return nil
        }
        var sawEncryption = false
        for format in formats {
            guard let value = number(format["encrypt"]) else { continue }
            sawEncryption = true
            if value != 0 { return value }
        }
        return sawEncryption ? 0 : nil
    }

    private func backupURLs(
        in object: [String: Any],
        excluding primary: URL
    ) -> [URL] {
        guard let values = object["backupurls"] as? [String] else {
            return []
        }
        var seen = Set([primary.absoluteString])
        var result: [URL] = []
        for value in values {
            guard result.count < 4 else { break }
            guard
                let url = URL(string: value),
                url.scheme?.lowercased() == "https",
                !url.path.isEmpty,
                url.path.lowercased().hasSuffix(".m3u8"),
                url.host?.isEmpty == false
            else {
                continue
            }
            if seen.insert(url.absoluteString).inserted {
                result.append(url)
            }
        }
        return result
    }

    private func updateQualityLadder(
        formats: [[String: Any]]?,
        currentDefinition: String?
    ) {
        guard let formats, !formats.isEmpty else {
            qualityLadder = []
            qualityIndex = -1
            return
        }
        var options: [(ordinal: Int, option: QualityOption)] = []
        for (ordinal, format) in formats.enumerated() {
            let fn = stringValue(format["fn"])
            guard !fn.isEmpty, fn.lowercased() != "audio" else { continue }
            let fnName = stringValue(format["fnname"])
            let defnName = stringValue(format["defnname"])
            let defnRate = stringValue(format["defnrate"])
            if fnName.lowercased().contains("audio") ||
                defnName.lowercased().contains("audio") {
                continue
            }
            let label = [fnName, defnName, defnRate, fn].first { !$0.isEmpty } ?? fn
            let score = [fn, fnName, defnName, defnRate].map(qualityScore).max() ?? 0
            options.append((ordinal, QualityOption(fn: fn, label: label, score: score)))
        }
        options.sort {
            if $0.option.score != $1.option.score {
                return $0.option.score > $1.option.score
            }
            return $0.ordinal > $1.ordinal
        }
        var seen = Set<String>()
        qualityLadder = options.compactMap { item in
            seen.insert(item.option.fn).inserted ? item.option : nil
        }
        qualityIndex = qualityLadder.firstIndex {
            $0.fn == (currentDefinition ?? "")
        } ?? (qualityLadder.isEmpty ? -1 : 0)
    }

    private func stringValue(_ value: Any?) -> String {
        if let value = value as? String { return value.trimmingCharacters(in: .whitespaces) }
        if let value = value as? NSNumber { return value.stringValue }
        return ""
    }

    private func boolValue(_ value: Any?) -> Bool? {
        if let value = value as? NSNumber { return value.boolValue }
        if let value = value as? Bool { return value }
        return nil
    }

    private func qualityScore(_ raw: String) -> Int {
        if raw.isEmpty { return 0 }
        let lower = raw.lowercased()
        var best = 0
        if lower.contains("8k") {
            best = 4320
        } else if lower.contains("4k") || lower.contains("2160") {
            best = 2160
        } else if lower.contains("2k") || lower.contains("1440") {
            best = 1440
        }
        for token in lower.components(separatedBy: CharacterSet.decimalDigits.inverted) {
            guard let value = Int(token), (240...4320).contains(value) else { continue }
            best = max(best, value)
        }
        if best == 0 {
            if lower.contains("蓝光") {
                best = 1080
            } else if lower.contains("超清") {
                best = 720
            } else if lower.contains("高清") {
                best = 480
            } else if lower.contains("标清") {
                best = 360
            }
        }
        if best == 0 {
            switch lower.trimmingCharacters(in: .whitespaces) {
            case "uhd8", "8k": best = 4320
            case "uhd", "4k": best = 2160
            case "qhd", "2k": best = 1440
            case "fhd": best = 1080
            case "shd": best = 720
            case "hd": best = 540
            case "sd": best = 360
            default: break
            }
        }
        return best
    }

    private func requestLowerOfficialQuality(generation token: Int) -> Bool {
        guard
            token == generation,
            renderMode == .official,
            !playingCctvFallback
        else {
            return false
        }
        let next = qualityIndex + 1
        guard next >= 0, next < qualityLadder.count else { return false }
        qualityIndex = next
        let option = qualityLadder[next]
        guard
            let data = try? JSONSerialization.data(
                withJSONObject: [option.fn],
                options: []
            ),
            let encoded = String(data: data, encoding: .utf8),
            encoded.hasPrefix("["),
            encoded.hasSuffix("]")
        else {
            return false
        }
        let quoted = String(encoded.dropFirst().dropLast())
        pendingChannel = currentChannel
        usesOfficialPlayer = true
        webView.isHidden = false
        webView.alpha = 1
        playingBackup = false
        officialHasVideoFrame = false
        officialPlaying = false
        officialProbeInFlight = false
        officialStartedAt = Date()
        officialLastProgressAt = Date()
        officialLastPosition = 0
        officialVideoId = nil
        officialHealthySince = nil
        isPreparing = true
        isBuffering = true
        status = L10n.t(
            "status.quality_lowering",
            L10n.qualityLabel(option.label)
        )
        authTimeoutTask?.cancel()
        if let channel = currentChannel {
            authTimeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 12_000_000_000)
                guard !Task.isCancelled else { return }
                await MainActor.run {
                    guard
                        let self,
                        token == self.generation,
                        self.renderMode == .official,
                        self.pendingChannel?.pid == channel.pid,
                        !self.playingCctvFallback
                    else {
                        return
                    }
                    self.authTimeoutTask = nil
                    self.pendingChannel = nil
                    self.qualityIndex = self.qualityLadder.count
                    self.officialRecoveryAttempts = 0
                    self.lastWatchdogActionAt = .distantPast
                    self.officialLastProgressAt = .distantPast
                    self.isBuffering = true
                    self.status = L10n.t("status.quality_timeout")
                }
            }
        }
        let script = """
        (function () {
          try {
            return Boolean(
              window.__yspApplyLiveQuality &&
              window.__yspApplyLiveQuality(\(quoted))
            );
          } catch (_) {
            return false;
          }
        })();
        """
        webView.evaluateJavaScript(script) { [weak self] result, _ in
            DispatchQueue.main.async {
                guard let self, token == self.generation else { return }
                let accepted = (result as? Bool)
                    ?? (result as? NSNumber)?.boolValue
                    ?? false
                if !accepted {
                    self.pendingChannel = nil
                    self.qualityIndex = self.qualityLadder.count
                    self.officialLastProgressAt = Date.distantPast
                }
            }
        }
        return true
    }

    @discardableResult
    private func tryNextBackup(generation token: Int, reason: String) -> Bool {
        guard
            token == generation,
            !playingCctvFallback,
            backupIndex >= 0,
            backupIndex < backupUrls.count,
            renderMode == .official || renderMode == .native
        else {
            return false
        }
        let url = backupUrls[backupIndex]
        backupIndex += 1
        playingBackup = true
        configureAudioSession()
        lastWatchdogActionAt = Date()
        lastProgressAt = Date()
        officialLastProgressAt = Date()
        logPlayback(
            "backup \(backupIndex)/\(backupUrls.count) host=\(hostLabel(url)) " +
            "reason=\(safeLogReason(reason))"
        )

        if renderMode == .native {
            startNativePlayback(
                url: url,
                generation: token,
                isBackup: true
            )
            return true
        }

        pendingChannel = nil
        usesOfficialPlayer = true
        webView.isHidden = false
        webView.alpha = 1
        isPreparing = true
        isBuffering = false
        hasRenderedFrame = false
        officialHasVideoFrame = false
        officialPlaying = false
        officialProbeInFlight = false
        officialStartedAt = Date()
        officialLastProgressAt = Date()
        officialLastPosition = 0
        officialVideoId = nil
        officialHealthySince = nil
        status = L10n.t("status.switching_backup")
        startWatchdog(generation: token)

        guard
            let data = try? JSONSerialization.data(
                withJSONObject: [url.absoluteString],
                options: []
            ),
            let encoded = String(data: data, encoding: .utf8),
            encoded.hasPrefix("["),
            encoded.hasSuffix("]")
        else {
            playingBackup = false
            return false
        }
        let quoted = String(encoded.dropFirst().dropLast())
        let script = """
        (function () {
          try {
            window.__yspSetNativeMode && window.__yspSetNativeMode(false);
            return Boolean(
              window.__yspPlayBackupUrl &&
              window.__yspPlayBackupUrl(\(quoted))
            );
          } catch (_) {
            return false;
          }
        })();
        """
        webView.evaluateJavaScript(script) { [weak self] result, _ in
            DispatchQueue.main.async {
                guard let self, token == self.generation else { return }
                let accepted = (result as? Bool)
                    ?? (result as? NSNumber)?.boolValue
                    ?? false
                if accepted {
                    self.status = L10n.t("status.starting_backup")
                    return
                }
                self.logPlayback(
                    "backup \(self.backupIndex)/\(self.backupUrls.count) " +
                    "was rejected by the page"
                )
                self.playingBackup = false
                if !self.tryNextBackup(
                    generation: token,
                    reason: "备用线路启动失败"
                ) {
                    self.failPlayback(
                        title: L10n.t("title.backup_unavailable"),
                        message: L10n.t("msg.backup_unavailable")
                    )
                }
            }
        }
        return true
    }

    private func cctvFallbackURL(for channel: Channel) -> URL? {
        guard Self.cctvWebFallbackEnabled else { return nil }
        guard channel.group == .cctv else { return nil }
        let name = channel.channelName
            .uppercased()
            .replacingOccurrences(of: "＋", with: "+")
            .replacingOccurrences(of: "（", with: "(")
            .replacingOccurrences(of: "）", with: ")")
            .replacingOccurrences(of: " ", with: "")
            .replacingOccurrences(of: "－", with: "-")
        guard let path = Self.cctvPagePaths[name] else { return nil }
        return URL(string: "https://tv.cctv.com/live/\(path)/m/")
    }

    private func startNativePlayback(
        url: URL,
        generation token: Int,
        isBackup: Bool = false
    ) {
        guard token == generation else { return }
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        tearDownNativePlayer()
        renderMode = .native
        usesOfficialPlayer = false
        playingBackup = isBackup
        webView.isHidden = true
        webView.alpha = 0.01
        isPreparing = true
        isBuffering = false
        hasRenderedFrame = false
        officialHasVideoFrame = false
        lastObservedTime = .invalid
        lastProgressAt = Date()
        status = isBackup
            ? L10n.t("status.starting_backup")
            : L10n.t(
                "status.starting_channel",
                currentChannel?.channelName ?? L10n.t("live")
            )
        logPlayback(
            "native start backup=\(isBackup) host=\(url.host ?? "unknown")"
        )

        let headers: [String: String] = [
            "Referer": "https://yangshipin.cn/",
            "User-Agent": Self.userAgent
        ]
        let asset = AVURLAsset(
            url: url,
            options: ["AVURLAssetHTTPHeaderFieldsKey": headers]
        )
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 6
        item.canUseNetworkResourcesForLiveStreamingWhilePaused = true
        let newPlayer = AVPlayer(playerItem: item)
        newPlayer.automaticallyWaitsToMinimizeStalling = true
        player = newPlayer
        itemStatusObservation?.invalidate()
        itemStatusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            DispatchQueue.main.async {
                guard
                    let self,
                    token == self.generation,
                    self.renderMode == .native,
                    item === self.player?.currentItem
                else {
                    return
                }
                switch item.status {
                case .readyToPlay:
                    if !self.hasRenderedFrame {
                        self.status = L10n.t(
                            "status.starting_channel",
                            self.currentChannel?.channelName ?? L10n.t("live")
                        )
                    }
                case .failed:
                    self.failPlayback(
                        title: L10n.t("title.playback_failed"),
                        message: item.error?.localizedDescription
                            ?? L10n.t("msg.video_load_failed")
                    )
                default:
                    break
                }
            }
        }
        timeControlObservation?.invalidate()
        timeControlObservation = newPlayer.observe(\.timeControlStatus, options: [.new]) { [weak self] player, _ in
            DispatchQueue.main.async {
                guard
                    let self,
                    token == self.generation,
                    self.renderMode == .native,
                    player === self.player
                else {
                    return
                }
                self.isBuffering = player.timeControlStatus == .waitingToPlayAtSpecifiedRate
                if self.isBuffering {
                    self.status = L10n.t("status.buffering")
                } else if self.hasRenderedFrame {
                    self.status = self.playingBackup
                        ? L10n.t("status.playing_backup")
                        : L10n.t("status.playing")
                }
            }
        }

        addNativeTimeObserver(to: newPlayer, generation: token)
        configureAudioSession()
        webView.evaluateJavaScript(
            """
            (function () {
              try {
                window.__yspSetNativeMode && window.__yspSetNativeMode(true);
                window.__yspPauseOfficialVideo && window.__yspPauseOfficialVideo();
              } catch (_) {}
            })();
            """
        ) { _, _ in }
        newPlayer.playImmediately(atRate: 1)
        startWatchdog(generation: token)
    }

    private func addNativeTimeObserver(to player: AVPlayer, generation token: Int) {
        removeNativeTimeObserver()
        let interval = CMTime(seconds: 0.5, preferredTimescale: 600)
        periodicTimeObserver = player.addPeriodicTimeObserver(
            forInterval: interval,
            queue: .main
        ) { [weak self, weak player] time in
            guard let self, let player else { return }
            self.observeNativeProgress(time, player: player, generation: token)
        }
        observedPlayer = player
    }

    private func removeNativeTimeObserver() {
        if let periodicTimeObserver, let observedPlayer {
            observedPlayer.removeTimeObserver(periodicTimeObserver)
        }
        periodicTimeObserver = nil
        observedPlayer = nil
    }

    private func observeNativeProgress(
        _ time: CMTime,
        player: AVPlayer,
        generation token: Int
    ) {
        guard
            token == generation,
            renderMode == .native,
            player === self.player
        else {
            return
        }
        let seconds = time.seconds
        guard seconds.isFinite else { return }
        defer { lastObservedTime = time }
        guard lastObservedTime.isValid else { return }
        let previous = lastObservedTime.seconds
        guard previous.isFinite else { return }
        let delta = seconds - previous
        guard delta > 0.04, delta < 30 else { return }

        lastProgressAt = Date()
        if !hasRenderedFrame {
            hasRenderedFrame = true
            hasVisibleVideo = true
            isPreparing = false
            isBuffering = false
            status = playingBackup
                ? L10n.t("status.playing_backup")
                : L10n.t("status.playing")
        } else if !isBuffering {
            status = playingBackup
                ? L10n.t("status.playing_backup")
                : L10n.t("status.playing")
        }
        if nativeRecoveryAttempts > 0,
           Date().timeIntervalSince(lastWatchdogActionAt) >= 10 {
            nativeRecoveryAttempts = 0
        }
    }

    private func startWatchdog(generation token: Int) {
        stopWatchdog()
        watchdogStartedAt = Date()
        lastWatchdogActionAt = .distantPast
        let timer = Timer(timeInterval: 1, repeats: true) { [weak self] _ in
            self?.watchdogTick(generation: token)
        }
        RunLoop.main.add(timer, forMode: .common)
        watchdogTimer = timer
    }

    private func stopWatchdog() {
        watchdogTimer?.invalidate()
        watchdogTimer = nil
    }

    private func watchdogTick(generation token: Int) {
        guard token == generation else {
            stopWatchdog()
            return
        }
        guard isForeground, errorMessage == nil else { return }

        switch renderMode {
        case .idle:
            break

        case .native:
            let elapsed = Date().timeIntervalSince(watchdogStartedAt)
            if !hasRenderedFrame, elapsed >= 10 {
                if !playingBackup,
                   tryNextBackup(
                       generation: token,
                       reason: "原生主线路起播超时"
                   ) {
                    return
                }
                if !usedOfficialFallback {
                    usedOfficialFallback = true
                    beginOfficialPlayback(generation: token, automaticFallback: true)
                } else {
                    failPlayback(
                        title: L10n.t("title.video_start_timeout"),
                        message: L10n.t("msg.video_start_timeout")
                    )
                }
                return
            }

            guard hasRenderedFrame else { return }
            let stalledFor = Date().timeIntervalSince(lastProgressAt)
            let cooldown = Date().timeIntervalSince(lastWatchdogActionAt)
            if stalledFor >= 4, cooldown >= 4,
               player?.timeControlStatus != .paused {
                recoverNativeStall(generation: token)
            }

        case .official:
            if pendingChannel != nil { return }
            if officialProbeInFlight,
               Date().timeIntervalSince(officialProbeStartedAt) >= 3 {
                officialProbeInFlight = false
            }
            if !officialProbeInFlight {
                probeOfficialState(generation: token)
            }
            let elapsed = Date().timeIntervalSince(officialStartedAt)
            let stalledFor = Date().timeIntervalSince(officialLastProgressAt)
            let cooldown = Date().timeIntervalSince(lastWatchdogActionAt)
            let startupThreshold: TimeInterval = playingCctvFallback
                ? 30
                : (playingBackup ? 18 : 10)
            let stallThreshold: TimeInterval = playingCctvFallback
                ? 18
                : (playingBackup ? 18 : 6)
            if (!officialPlaying || !officialHasVideoFrame),
               elapsed >= startupThreshold,
               cooldown >= 4 {
                recoverOfficialStall(generation: token, startup: true)
            } else if officialPlaying, stalledFor >= stallThreshold, cooldown >= 6 {
                recoverOfficialStall(generation: token, startup: false)
            }
        }
    }

    private func recoverOfficialStall(generation token: Int, startup: Bool) {
        guard token == generation, renderMode == .official else { return }
        lastWatchdogActionAt = Date()
        officialLastProgressAt = Date()
        isBuffering = true
        logPlayback(
            "official stall startup=\(startup) backup=\(playingBackup) " +
            "attempt=\(officialRecoveryAttempts) visible=\(hasVisibleVideo)"
        )

        if officialRecoveryAttempts == 0 {
            requestOfficialRecovery(generation: token)
            return
        }
        if !playingCctvFallback,
           tryNextBackup(
               generation: token,
               reason: startup ? "主线路起播超时" : "主线路持续卡顿"
           ) {
            officialRecoveryAttempts = 0
            return
        }
        if !playingCctvFallback,
           officialRecoveryAttempts == 1,
           requestLowerOfficialQuality(generation: token) {
            officialRecoveryAttempts += 1
            return
        }
        if !playingCctvFallback,
           let channel = currentChannel,
           beginCctvFallback(for: channel, reason: startup ? "主线路起播超时" : "主线路持续卡顿") {
            officialRecoveryAttempts = 0
            return
        }
        if officialRecoveryAttempts < 2 {
            requestOfficialRecovery(generation: token)
            return
        }
        failPlayback(
            title: playingCctvFallback
                ? L10n.t("title.cctv_stalled")
                : L10n.t("title.live_stalled"),
            message: L10n.t("msg.live_stalled")
        )
    }

    private func recoverNativeStall(generation token: Int) {
        guard token == generation, renderMode == .native else { return }
        lastWatchdogActionAt = Date()
        lastProgressAt = Date()
        nativeRecoveryAttempts += 1
        isBuffering = true

        if nativeRecoveryAttempts == 1 {
            status = L10n.t("status.recovering_live")
            seekToLiveEdge()
        } else if nativeRecoveryAttempts == 2, let channel = currentChannel {
            if tryNextBackup(
                generation: token,
                reason: "原生播放持续卡顿"
            ) {
                return
            }
            status = L10n.t("status.reconnecting")
            beginPlay(channel, resetRecovery: false)
        } else if nativeRecoveryAttempts == 3,
                  !usedOfficialFallback {
            usedOfficialFallback = true
            beginOfficialPlayback(generation: token, automaticFallback: true)
            return
        } else {
            failPlayback(
                title: L10n.t("title.playback_stalled"),
                message: L10n.t("msg.playback_stalled")
            )
        }
    }

    private func seekToLiveEdge() {
        guard let player else { return }
        lastObservedTime = .invalid
        var target = CMTime.positiveInfinity
        if let rangeValue = player.currentItem?.seekableTimeRanges.last {
            let range = rangeValue.timeRangeValue
            let end = CMTimeAdd(range.start, range.duration)
            if end.isValid, end.seconds.isFinite, end.seconds > 0 {
                target = CMTimeSubtract(
                    end,
                    CMTime(seconds: 0.25, preferredTimescale: 600)
                )
            }
        }
        player.seek(
            to: target,
            toleranceBefore: .zero,
            toleranceAfter: .zero
        )
        player.playImmediately(atRate: 1)
    }

    private func beginOfficialPlayback(
        generation token: Int,
        automaticFallback: Bool
    ) {
        guard token == generation, currentChannel != nil else { return }
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        pendingChannel = nil
        tearDownNativePlayer()
        renderMode = .official
        usesOfficialPlayer = true
        playingCctvFallback = false
        playingBackup = false
        webView.customUserAgent = Self.userAgent
        webView.isHidden = false
        webView.alpha = 1
        isPreparing = true
        isBuffering = false
        hasRenderedFrame = false
        officialHasVideoFrame = false
        officialPlaying = false
        officialProbeInFlight = false
        officialStartedAt = Date()
        officialLastProgressAt = Date()
        officialLastPosition = 0
        officialVideoId = nil
        officialHealthySince = nil
        if automaticFallback {
            officialRecoveryAttempts = 0
        }
        configureAudioSession()
        status = automaticFallback
            ? L10n.t("status.switching_compatible")
            : L10n.t("status.starting_official")
        logPlayback(
            "official start automatic=\(automaticFallback) " +
            "backup=\(playingBackup)"
        )
        webView.evaluateJavaScript(
            "window.__yspStopBackupPlayer && window.__yspStopBackupPlayer();"
        ) { _, _ in }
        startWatchdog(generation: token)
        resumeOfficialVideo(generation: token, retries: 6)
    }

    @discardableResult
    private func beginCctvFallback(for channel: Channel, reason: String) -> Bool {
        guard Self.cctvWebFallbackEnabled else { return false }
        guard !cctvFallbackUsed else { return false }
        guard let url = cctvFallbackURL(for: channel) else { return false }
        let token = generation
        cctvFallbackUsed = true
        playingCctvFallback = true
        fallbackRetryTask?.cancel()
        fallbackRetryTask = nil
        fallbackRetryAttempts = 0
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        pendingChannel = nil
        stopWatchdog()
        tearDownNativePlayer()
        renderMode = .official
        usesOfficialPlayer = true
        webView.customUserAgent = Self.cctvUserAgent
        webView.isHidden = false
        webView.alpha = 1
        isPreparing = true
        isBuffering = false
        errorMessage = nil
        hasRenderedFrame = false
        officialHasVideoFrame = false
        officialPlaying = false
        officialProbeInFlight = false
        officialStartedAt = Date()
        officialLastProgressAt = Date()
        officialLastPosition = 0
        officialVideoId = nil
        officialRecoveryAttempts = 0
        officialHealthySince = nil
        status = L10n.t("status.cctv_switch")
        logPlayback(
            "cctv fallback start reason=\(safeLogReason(reason))"
        )
        startWatchdog(generation: token)

        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(Self.cctvUserAgent, forHTTPHeaderField: "User-Agent")
        webView.load(request)

        DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
            guard
                let self,
                token == self.generation,
                self.playingCctvFallback,
                self.renderMode == .official,
                self.isPreparing
            else {
                return
            }
            self.resumeOfficialVideo(generation: token, retries: 10)
        }
        status = L10n.t("status.cctv_switch")
        return true
    }

    private func scheduleCctvPageRetry(reason: String) {
        guard playingCctvFallback, let channel = currentChannel else { return }
        fallbackRetryTask?.cancel()
        if fallbackRetryAttempts >= 8 {
            failPlayback(
                title: L10n.t("title.cctv_unrecovered"),
                message: L10n.t("msg.cctv_unrecovered")
            )
            return
        }
        fallbackRetryAttempts += 1
        let attempt = fallbackRetryAttempts
        let token = generation
        let pid = channel.pid
        isPreparing = true
        isBuffering = true
        status = L10n.t("status.cctv_retry", attempt)
        fallbackRetryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard
                    let self,
                    token == self.generation,
                    self.playingCctvFallback,
                    self.currentChannel?.pid == pid,
                    let url = self.currentChannel.flatMap({
                        self.cctvFallbackURL(for: $0)
                    })
                else {
                    return
                }
                self.fallbackRetryTask = nil
                self.status = L10n.t("status.cctv_retry", attempt)
                var request = URLRequest(url: url)
                request.cachePolicy = .reloadIgnoringLocalCacheData
                request.setValue(
                    Self.cctvUserAgent,
                    forHTTPHeaderField: "User-Agent"
                )
                self.webView.load(request)
            }
        }
    }

    private func resumeOfficialVideo(generation token: Int, retries: Int) {
        guard token == generation, renderMode == .official else { return }
        let script = """
        (function () {
          try {
            window.__yspSetNativeMode && window.__yspSetNativeMode(false);
            return Boolean(
              window.__yspUseOfficialPlayer &&
              window.__yspUseOfficialPlayer()
            );
          } catch (_) {
            return false;
          }
        })();
        """
        webView.evaluateJavaScript(script) { [weak self] result, _ in
            DispatchQueue.main.async {
                guard
                    let self,
                    token == self.generation,
                    self.renderMode == .official
                else {
                    return
                }
                let succeeded = (result as? Bool)
                    ?? (result as? NSNumber)?.boolValue
                    ?? false
                if !succeeded, retries > 0 {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                        self.resumeOfficialVideo(
                            generation: token,
                            retries: retries - 1
                        )
                    }
                }
            }
        }
    }

    private func requestOfficialRecovery(generation token: Int) {
        guard token == generation, renderMode == .official else { return }
        officialRecoveryAttempts += 1
        officialHealthySince = nil
        lastWatchdogActionAt = Date()
        officialLastProgressAt = Date()
        isBuffering = true
        status = L10n.t("status.recovering_live")
        logPlayback(
            "official recovery attempt=\(officialRecoveryAttempts)"
        )
        let script = """
        (function () {
          try {
            window.__yspSetNativeMode && window.__yspSetNativeMode(false);
            return Boolean(
              window.__yspRecoverOfficialVideo &&
              window.__yspRecoverOfficialVideo()
            );
          } catch (_) {
            return false;
          }
        })();
        """
        webView.evaluateJavaScript(script) { _, _ in }
    }

    private func probeOfficialState(generation token: Int) {
        guard token == generation, renderMode == .official else { return }
        officialProbeInFlight = true
        officialProbeStartedAt = Date()
        let script = """
        (function () {
          try {
            return window.__yspGetOfficialState
              ? window.__yspGetOfficialState()
              : "";
          } catch (_) {
            return "";
          }
        })();
        """
        webView.evaluateJavaScript(script) { [weak self] result, _ in
            DispatchQueue.main.async {
                guard
                    let self,
                    token == self.generation,
                    self.renderMode == .official
                else {
                    return
                }
                self.officialProbeInFlight = false
                guard
                    let raw = result as? String,
                    let data = raw.data(using: .utf8),
                    let object = try? JSONSerialization.jsonObject(with: data),
                    let state = object as? [String: Any]
                else {
                    return
                }
                if let mediaError = state["error"] as? [String: Any],
                   let code = (mediaError["code"] as? NSNumber)?.intValue,
                   code != 0 {
                    self.failPlayback(
                            title: L10n.t("title.playback_failed"),
                            message: mediaError["message"] as? String
                                ?? L10n.t("msg.video_decode_failed")
                    )
                    return
                }
                let paused = (state["paused"] as? NSNumber)?.boolValue ?? true
                let current = (state["currentTime"] as? NSNumber)?.doubleValue ?? 0
                let width = (state["videoWidth"] as? NSNumber)?.intValue ?? 0
                let height = (state["videoHeight"] as? NSNumber)?.intValue ?? 0
                if width > 0, height > 0 {
                    self.officialHasVideoFrame = true
                }
                let videoId = state["videoId"] as? String
                let videoChanged =
                    videoId?.isEmpty == false &&
                    self.officialVideoId?.isEmpty == false &&
                    videoId != self.officialVideoId
                if let videoId, !videoId.isEmpty {
                    self.officialVideoId = videoId
                }
                if videoChanged {
                    self.officialHasVideoFrame = false
                    self.officialLastPosition = current
                    self.officialLastProgressAt = Date()
                    self.isPreparing = true
                    self.isBuffering = true
                    self.status = self.playingBackup
                        ? L10n.t("status.backup_switched_waiting")
                        : L10n.t("status.video_switched_waiting")
                    return
                }
                guard !paused, current.isFinite else { return }
                if current > self.officialLastPosition + 0.04 {
                    self.officialPlaying = true
                    self.officialLastProgressAt = Date()
                    if self.officialHasVideoFrame {
                        self.hasVisibleVideo = true
                        self.isPreparing = false
                        self.isBuffering = false
                        self.status = self.playingBackup
                            ? L10n.t("status.playing_backup")
                            : L10n.t("status.playing")
                    } else {
                        self.isBuffering = true
                        self.status = self.playingBackup
                            ? L10n.t("status.backup_started_waiting")
                            : L10n.t("status.video_started_waiting")
                    }
                    if self.officialHasVideoFrame {
                        if let healthySince = self.officialHealthySince {
                            if Date().timeIntervalSince(healthySince) >= 20 {
                                self.officialRecoveryAttempts = 0
                            }
                        } else {
                            self.officialHealthySince = Date()
                        }
                    }
                } else if current < self.officialLastPosition - 1 {
                    self.officialLastProgressAt = Date()
                }
                self.officialLastPosition = current
            }
        }
    }

    private func resumeAfterForeground() {
        let token = generation
        let inactiveFor = backgroundedAt.map { Date().timeIntervalSince($0) } ?? 0
        backgroundedAt = nil
        guard errorMessage == nil else { return }

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) { [weak self] in
            guard let self, token == self.generation else { return }
            switch self.renderMode {
            case .idle:
                break
            case .native:
                guard self.player != nil else { return }
                if self.hasRenderedFrame,
                   inactiveFor >= 8
                    || Date().timeIntervalSince(self.lastProgressAt) >= 3 {
                    self.status = L10n.t("status.recovering_live")
                    self.isBuffering = true
                    self.lastProgressAt = Date()
                    self.seekToLiveEdge()
                } else {
                    self.player?.playImmediately(atRate: 1)
                }
            case .official:
                let stalled = Date().timeIntervalSince(self.officialLastProgressAt) >= 3
                if self.officialPlaying, inactiveFor >= 8 || stalled {
                    self.requestOfficialRecovery(generation: token)
                } else {
                    self.resumeOfficialVideo(generation: token, retries: 3)
                }
            }
        }
    }

    private func failPlayback(title: String, message: String) {
        if !playingCctvFallback,
           tryNextBackup(
               generation: generation,
               reason: "\(title)：\(message)"
           ) {
            return
        }
        if renderMode == .native,
           !usedOfficialFallback {
            usedOfficialFallback = true
            beginOfficialPlayback(generation: generation, automaticFallback: true)
            return
        }
        if !cctvFallbackUsed,
           let channel = currentChannel,
           beginCctvFallback(for: channel, reason: "\(title)：\(message)") {
            return
        }
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        fallbackRetryTask?.cancel()
        fallbackRetryTask = nil
        stopWatchdog()
        pendingChannel = nil
        isPreparing = false
        isBuffering = false
        hasVisibleVideo = false
        status = title
        errorMessage = message
        tearDownNativePlayer()
        webView.isHidden = true
        if renderMode == .official {
            webView.evaluateJavaScript(
                "window.__yspPauseOfficialVideo && window.__yspPauseOfficialVideo();"
            ) { _, _ in }
        }
    }

    private func tearDownNativePlayer() {
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        timeControlObservation?.invalidate()
        timeControlObservation = nil
        removeNativeTimeObserver()
        player?.pause()
        player?.replaceCurrentItem(with: nil)
        player = nil
        hasRenderedFrame = false
        lastObservedTime = .invalid
    }

    private func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(
            .playback,
            mode: .moviePlayback,
            options: [.allowAirPlay, .allowBluetoothA2DP]
        )
        try? session.setActive(true)
    }

    private func logPlayback(_ message: String) {
        let safe = message.replacingOccurrences(
            of: #"https?://[^\s]+"#,
            with: "<url>",
            options: .regularExpression
        )
        NSLog("[YspPlayback] %@", safe)
    }

    private func safeLogReason(_ message: String) -> String {
        let safe = message.replacingOccurrences(
            of: #"https?://[^\s]+"#,
            with: "<url>",
            options: .regularExpression
        )
        return String(safe.prefix(160))
    }

    private func hostLabel(_ url: URL) -> String {
        url.host ?? "unknown"
    }

    @objc private func handleItemFailure(_ notification: Notification) {
        guard
            let item = notification.object as? AVPlayerItem,
            item === player?.currentItem
        else {
            return
        }
        DispatchQueue.main.async {
            guard self.renderMode == .native, item === self.player?.currentItem else {
                return
            }
            self.failPlayback(
                title: L10n.t("title.playback_failed"),
                message: item.error?.localizedDescription
                    ?? L10n.t("msg.video_playback_failed")
            )
        }
    }
}

private struct QualityOption {
    let fn: String
    let label: String
    let score: Int
}

extension PlaybackController: WKScriptMessageHandler {
    func userContentController(
        _ userContentController: WKUserContentController,
        didReceive message: WKScriptMessage
    ) {
        guard message.name == Self.bridgeName, let body = message.body as? String else {
            return
        }
        handleBridgeMessage(body)
    }
}

extension PlaybackController: WKNavigationDelegate {
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard
            renderMode == .official,
            playingCctvFallback,
            isPreparing
        else {
            return
        }
        status = L10n.t("status.cctv_page_loaded")
        resumeOfficialVideo(generation: generation, retries: 10)
    }

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.cancel)
            return
        }
        decisionHandler(Self.isAllowed(url) ? .allow : .cancel)
    }

    func webView(
        _ webView: WKWebView,
        didFailProvisionalNavigation navigation: WKNavigation!,
        withError error: Error
    ) {
        guard isPreparing || usesOfficialPlayer else { return }
        if playingCctvFallback {
            scheduleCctvPageRetry(
                reason: "央视备选网络中断：\(error.localizedDescription)"
            )
            return
        }
        failPlayback(
            title: L10n.t("title.auth_page_failed"),
            message: L10n.t("msg.auth_page_failed", error.localizedDescription)
        )
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard isPreparing || usesOfficialPlayer else { return }
        if playingCctvFallback {
            scheduleCctvPageRetry(reason: "央视备选页面已终止")
            return
        }
        failPlayback(
            title: L10n.t("title.auth_page_terminated"),
            message: L10n.t("msg.auth_page_terminated")
        )
    }

    private static func isAllowed(_ url: URL) -> Bool {
        guard let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else {
            return false
        }
        let host = (url.host ?? "").lowercased()
        return [
            "yangshipin.cn",
            "cctv.com",
            "cctv.cn",
            "cntv.cn"
        ].contains { host == $0 || host.hasSuffix(".\($0)") }
    }
}
