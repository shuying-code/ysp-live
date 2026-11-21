import AVFoundation
import Combine
import Foundation
import UIKit
import WebKit

final class PlaybackController: NSObject, ObservableObject {
    @Published private(set) var currentChannel: Channel?
    @Published private(set) var player: AVPlayer?
    @Published private(set) var status = "选择一个频道开始播放"
    @Published private(set) var isPreparing = false
    @Published private(set) var isBuffering = false
    @Published private(set) var usesOfficialPlayer = false
    @Published var errorMessage: String?

    let webView: WKWebView

    private static let bridgeName = "yspBridge"
    private static let knownEncryptedPids: Set<String> = [
        "600002493",
        "600002309",
        "600002521"
    ]
    // Match the Android auth WebView so the official page returns the same
    // player payload and HLS streams.
    private static let userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private enum RenderMode {
        case idle
        case native
        case official
    }

    private var renderMode = RenderMode.idle
    private var generation = 0
    private var pendingChannel: Channel?
    private var authTimeoutTask: Task<Void, Never>?
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
    private var officialStartedAt = Date()
    private var officialPlaying = false
    private var officialProbeInFlight = false
    private var officialLastPosition = 0.0
    private var officialLastProgressAt = Date.distantPast
    private var officialRecoveryAttempts = 0
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
                self.status = "缓冲中…"
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
            errorMessage = "频道缺少 pid，无法播放。"
            return
        }

        generation &+= 1
        let token = generation
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        stopWatchdog()
        tearDownNativePlayer()

        currentChannel = channel
        pendingChannel = channel
        renderMode = .idle
        usesOfficialPlayer = false
        isPreparing = true
        isBuffering = false
        errorMessage = nil
        status = "正在加载官方鉴权页…"
        hasRenderedFrame = false
        lastObservedTime = .invalid
        lastProgressAt = Date()
        officialPlaying = false
        officialProbeInFlight = false
        webView.isHidden = false
        webView.alpha = 0.01
        if resetRecovery {
            nativeRecoveryAttempts = 0
            usedOfficialFallback = false
            officialRecoveryAttempts = 0
        }

        guard let url = URL(string: "https://yangshipin.cn/tv/home?pid=\(channel.pid)") else {
            isPreparing = false
            errorMessage = "频道地址无效。"
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
                    title: "取流超时",
                    message: "官方播放器 20 秒内没有返回播放地址。请检查网络，或换一个频道重试。"
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
        stopWatchdog()
        tearDownNativePlayer()
        pendingChannel = nil
        renderMode = .idle
        usesOfficialPlayer = false
        isPreparing = false
        isBuffering = false
        hasRenderedFrame = false
        status = "选择一个频道开始播放"
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
                status = "官方页面已加载，等待鉴权结果…"
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
                    title: "取流失败",
                    message: "官方返回的播放地址无效。"
                )
                return
            }
            if let livePid = object["livepid"] as? String,
               !livePid.isEmpty,
               livePid != channel.pid {
                return
            }
            let token = generation
            let encryption = encryptionFlag(in: object)
            pendingChannel = nil
            authTimeoutTask?.cancel()
            authTimeoutTask = nil
            if encryption != nil, encryption != 0 {
                beginOfficialPlayback(generation: token, automaticFallback: false)
            } else if Self.knownEncryptedPids.contains(channel.pid) {
                beginOfficialPlayback(generation: token, automaticFallback: false)
            } else {
                startNativePlayback(url: url, generation: token)
            }

        case "playing":
            guard renderMode == .official else { return }
            officialPlaying = true
            officialLastProgressAt = Date()
            isPreparing = false
            isBuffering = false
            status = "直播中"

        case "buffering":
            guard renderMode == .official else { return }
            isBuffering = true
            status = "缓冲中…"

        case "video_size":
            guard renderMode == .official else { return }
            let width = (object["width"] as? NSNumber)?.intValue ?? 0
            let height = (object["height"] as? NSNumber)?.intValue ?? 0
            if width > 0, height > 0 {
                officialLastProgressAt = Date()
            }

        case "playback_error":
            let message = object["message"] as? String ?? "官方播放器返回错误。"
            if renderMode == .official || isPreparing {
                failPlayback(title: "播放失败", message: message)
            }

        case "error":
            let message = object["message"] as? String ?? "官方播放器返回错误。"
            if isPreparing { failPlayback(title: "取流失败", message: message) }

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

    private func startNativePlayback(url: URL, generation token: Int) {
        guard token == generation else { return }
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        renderMode = .native
        usesOfficialPlayer = false
        webView.isHidden = true
        webView.alpha = 0.01
        isPreparing = true
        isBuffering = false
        hasRenderedFrame = false
        lastObservedTime = .invalid
        lastProgressAt = Date()
        status = "正在起播 · \(currentChannel?.channelName ?? "直播")"

        let headers: [String: String] = [
            "Referer": "https://yangshipin.cn/",
            "User-Agent": Self.userAgent
        ]
        let asset = AVURLAsset(
            url: url,
            options: ["AVURLAssetHTTPHeaderFieldsKey": headers]
        )
        let item = AVPlayerItem(asset: asset)
        item.preferredForwardBufferDuration = 2
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
                        self.status = "正在起播 · \(self.currentChannel?.channelName ?? "直播")"
                    }
                case .failed:
                    self.failPlayback(
                        title: "播放失败",
                        message: item.error?.localizedDescription ?? "视频加载失败，请刷新重试。"
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
                    self.status = "缓冲中…"
                } else if self.hasRenderedFrame {
                    self.status = "直播中"
                }
            }
        }

        addNativeTimeObserver(to: newPlayer, generation: token)
        configureAudioSession()
        newPlayer.playImmediately(atRate: 1)
        startWatchdog(generation: token)

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
            isPreparing = false
            isBuffering = false
            status = "直播中"
        } else if !isBuffering {
            status = "直播中"
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
                if !usedOfficialFallback {
                    usedOfficialFallback = true
                    beginOfficialPlayback(generation: token, automaticFallback: true)
                } else {
                    failPlayback(
                        title: "视频启动超时",
                        message: "原生播放器 10 秒内没有出帧，官方兼容播放器也未能启动。请重新连接。"
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
            if !officialProbeInFlight {
                probeOfficialState(generation: token)
            }
            let elapsed = Date().timeIntervalSince(officialStartedAt)
            let stalledFor = Date().timeIntervalSince(officialLastProgressAt)
            let cooldown = Date().timeIntervalSince(lastWatchdogActionAt)
            if !officialPlaying, elapsed >= 12, cooldown >= 5 {
                if officialRecoveryAttempts == 0 {
                    requestOfficialRecovery(generation: token)
                } else {
                    failPlayback(
                        title: "官方播放器启动超时",
                        message: "官方加密播放器未能出帧。请检查网络后重新连接。"
                    )
                }
            } else if officialPlaying, stalledFor >= 4, cooldown >= 4 {
                if officialRecoveryAttempts < 2 {
                    requestOfficialRecovery(generation: token)
                } else {
                    failPlayback(
                        title: "直播已停滞",
                        message: "官方播放器连续 4 秒没有推进，已停止自动恢复。请重新连接。"
                    )
                }
            }
        }
    }

    private func recoverNativeStall(generation token: Int) {
        guard token == generation, renderMode == .native else { return }
        lastWatchdogActionAt = Date()
        lastProgressAt = Date()
        nativeRecoveryAttempts += 1
        isBuffering = true

        if nativeRecoveryAttempts == 1 {
            status = "正在恢复直播…"
            seekToLiveEdge()
        } else if nativeRecoveryAttempts == 2, let channel = currentChannel {
            status = "正在重新连接…"
            beginPlay(channel, resetRecovery: false)
        } else {
            failPlayback(
                title: "播放已停滞",
                message: "直播画面连续 4 秒没有推进。请点“重新连接”重新鉴权。"
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
        webView.isHidden = false
        webView.alpha = 1
        isPreparing = true
        isBuffering = false
        hasRenderedFrame = false
        officialPlaying = false
        officialProbeInFlight = false
        officialStartedAt = Date()
        officialLastProgressAt = Date()
        officialLastPosition = 0
        status = automaticFallback
            ? "正在切换兼容播放器…"
            : "正在启动官方播放器…"
        startWatchdog(generation: token)
        resumeOfficialVideo(generation: token, retries: 6)
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
        lastWatchdogActionAt = Date()
        officialLastProgressAt = Date()
        isBuffering = true
        status = "正在恢复直播…"
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
                        title: "播放失败",
                        message: mediaError["message"] as? String ?? "视频解码失败。"
                    )
                    return
                }
                let paused = (state["paused"] as? NSNumber)?.boolValue ?? true
                let current = (state["currentTime"] as? NSNumber)?.doubleValue ?? 0
                guard !paused, current.isFinite else { return }
                if current > self.officialLastPosition + 0.04 {
                    self.officialPlaying = true
                    self.isPreparing = false
                    self.isBuffering = false
                    self.status = "直播中"
                    self.officialLastProgressAt = Date()
                    self.officialRecoveryAttempts = 0
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
                    self.status = "正在恢复直播…"
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
        authTimeoutTask?.cancel()
        authTimeoutTask = nil
        stopWatchdog()
        pendingChannel = nil
        isPreparing = false
        isBuffering = false
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
                title: "播放失败",
                message: item.error?.localizedDescription ?? "视频播放失败，请刷新重试。"
            )
        }
    }
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
        failPlayback(
            title: "鉴权页加载失败",
            message: "无法加载官方鉴权页：\(error.localizedDescription)"
        )
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard isPreparing || usesOfficialPlayer else { return }
        failPlayback(
            title: "鉴权页已终止",
            message: "官方鉴权页被系统终止，请重新选择频道。"
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
