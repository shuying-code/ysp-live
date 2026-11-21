import AVFoundation
import Combine
import Foundation
import WebKit

final class PlaybackController: NSObject, ObservableObject {
    @Published private(set) var currentChannel: Channel?
    @Published private(set) var player: AVPlayer?
    @Published private(set) var status = "选择一个频道开始播放"
    @Published private(set) var isPreparing = false
    @Published var isFullScreen = false
    @Published var errorMessage: String?

    let webView: WKWebView

    private static let bridgeName = "yspBridge"
    // Match the Android auth WebView: the TV page is verified against a
    // desktop Chromium user agent, and it is hidden on iOS anyway.
    private static let userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private var pendingChannel: Channel?
    private var timeoutTask: Task<Void, Never>?
    private var itemStatusObservation: NSKeyValueObservation?
    private var observers: [NSObjectProtocol] = []

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
                self?.status = "缓冲中…"
            }
        )
    }

    deinit {
        timeoutTask?.cancel()
        itemStatusObservation?.invalidate()
        NotificationCenter.default.removeObserver(self)
        observers.forEach { NotificationCenter.default.removeObserver($0) }
    }

    func play(_ channel: Channel) {
        guard !channel.pid.isEmpty else {
            errorMessage = "频道缺少 pid，无法播放。"
            return
        }

        currentChannel = channel
        pendingChannel = channel
        isPreparing = true
        isFullScreen = true
        errorMessage = nil
        status = "正在加载官方鉴权页…"
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        player?.pause()
        player = nil
        timeoutTask?.cancel()

        guard let url = URL(string: "https://yangshipin.cn/tv/home?pid=\(channel.pid)") else {
            isPreparing = false
            errorMessage = "频道地址无效。"
            return
        }
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue(Self.userAgent, forHTTPHeaderField: "User-Agent")
        webView.load(request)

        timeoutTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                guard let self, self.isPreparing else { return }
                self.isPreparing = false
                self.status = "取流超时"
                self.errorMessage = "官方播放器 20 秒内没有返回 HLS 地址。请检查网络，或换一个频道重试。"
            }
        }
    }

    func reload() {
        if let channel = currentChannel {
            play(channel)
        }
    }

    func stop() {
        timeoutTask?.cancel()
        timeoutTask = nil
        itemStatusObservation?.invalidate()
        itemStatusObservation = nil
        pendingChannel = nil
        player?.pause()
        player = nil
        isPreparing = false
        isFullScreen = false
        status = "选择一个频道开始播放"
        webView.stopLoading()
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
            if isPreparing {
                status = "官方页面已加载，等待鉴权结果…"
            }

        case "live_info":
            guard pendingChannel != nil else { return }
            guard
                let rawURL = object["playurl"] as? String,
                let url = URL(string: rawURL),
                url.scheme?.lowercased() == "https",
                url.path.lowercased().hasSuffix(".m3u8")
            else {
                isPreparing = false
                errorMessage = "官方返回的播放地址无效。"
                return
            }
            if let livePid = object["livepid"] as? String,
               !livePid.isEmpty,
               let expected = pendingChannel?.pid,
               livePid != expected {
                return
            }
            startPlayback(url: url)

        case "playback_error", "error":
            let message = object["message"] as? String ?? "官方播放器返回错误。"
            if isPreparing {
                isPreparing = false
                errorMessage = message
                status = "取流失败"
            }

        default:
            break
        }
    }

    private func startPlayback(url: URL) {
        timeoutTask?.cancel()
        timeoutTask = nil
        pendingChannel = nil
        isPreparing = false
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
                guard let self else { return }
                switch item.status {
                case .readyToPlay:
                    self.status = "直播中"
                case .failed:
                    self.status = "播放失败"
                    self.errorMessage = item.error?.localizedDescription ?? "视频加载失败，请刷新重试。"
                default:
                    break
                }
            }
        }

        configureAudioSession()
        newPlayer.play()

        webView.evaluateJavaScript(
            "window.__yspPauseOfficialVideo && window.__yspPauseOfficialVideo();"
        ) { _, _ in }
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
            self.errorMessage = item.error?.localizedDescription ?? "视频播放失败，请刷新重试。"
            self.status = "播放失败"
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
        guard isPreparing else { return }
        isPreparing = false
        status = "鉴权页加载失败"
        errorMessage = "无法加载官方鉴权页：\(error.localizedDescription)"
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        guard isPreparing else { return }
        isPreparing = false
        status = "鉴权页已终止"
        errorMessage = "官方鉴权页被系统终止，请重新选择频道。"
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
