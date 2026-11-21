import AVFoundation
import Foundation
import SwiftUI
import UIKit
import WebKit

struct RootView: View {
    @EnvironmentObject private var playback: PlaybackController
    @Environment(\.scenePhase) private var scenePhase

    @State private var channels: [Channel] = []
    @State private var selectedChannel: Channel?
    @State private var drawerVisible = false
    @State private var drawerRevision = 0
    @State private var catalogError: String?
    @State private var guidePrograms: [EpgProgram] = []
    @State private var guideState: GuideState = .idle
    @State private var autoHideTask: Task<Void, Never>?
    @State private var started = false

    private static let lastPidKey = "last_pid"
    private static let defaultPid = "600152137"

    var body: some View {
        GeometryReader { proxy in
            let metrics = DrawerMetrics(size: proxy.size)
            ZStack {
                Color.black.ignoresSafeArea()

                if let player = playback.player {
                    PlayerLayerView(player: player)
                        .ignoresSafeArea()
                }

                AuthWebViewContainer(webView: playback.webView)
                    .frame(
                        width: playback.usesOfficialPlayer ? proxy.size.width : 1,
                        height: playback.usesOfficialPlayer ? proxy.size.height : 1
                    )
                    .opacity(playback.usesOfficialPlayer ? 1 : 0.01)
                    .allowsHitTesting(playback.usesOfficialPlayer)

                if channels.isEmpty && catalogError == nil {
                    LoadingOverlay(message: "正在读取频道目录…")
                        .zIndex(1)
                } else if playback.isPreparing && !playback.hasVisibleVideo {
                    LoadingOverlay(message: playback.status)
                        .zIndex(1)
                } else if playback.isPreparing || playback.isBuffering {
                    BufferingBadge(message: playback.status)
                        .zIndex(1)
                }

                if !drawerVisible {
                    Color.clear
                        .contentShape(Rectangle())
                        .ignoresSafeArea()
                        .onTapGesture {
                            guard !channels.isEmpty else { return }
                            openDrawer()
                        }
                        .zIndex(2)
                } else {
                    Color.black.opacity(0.001)
                        .contentShape(Rectangle())
                        .ignoresSafeArea()
                        .onTapGesture {
                            closeDrawer()
                        }
                        .zIndex(2)

                    HStack(spacing: 0) {
                        ChannelPane(
                            channels: channels,
                            selectedChannel: selectedChannel,
                            revision: drawerRevision,
                            onSelect: selectChannel
                        )
                        .frame(width: metrics.channelWidth)

                        Rectangle()
                            .fill(Color.white.opacity(0.2))
                            .frame(width: 1)

                        GuidePane(
                            channel: selectedChannel,
                            programs: guidePrograms,
                            state: guideState,
                            onRetry: retryGuide
                        )
                    }
                    .frame(width: metrics.fullWidth, height: proxy.size.height)
                    .background(AndroidTheme.drawerBackground)
                    .transition(.move(edge: .leading))
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
                    .zIndex(4)
                }

                if let error = catalogError {
                    ErrorOverlay(
                        title: "频道目录加载失败",
                        message: error,
                        primaryTitle: "重新读取",
                        primaryAction: {
                            Task { await loadCatalog() }
                        },
                        secondaryTitle: nil,
                        secondaryAction: nil
                    )
                    .zIndex(5)
                } else if let error = playback.errorMessage {
                    ErrorOverlay(
                        title: "播放异常",
                        message: error,
                        primaryTitle: "重新连接",
                        primaryAction: playback.reload,
                        secondaryTitle: "选择频道",
                        secondaryAction: {
                            playback.errorMessage = nil
                            openDrawer()
                        }
                    )
                    .zIndex(5)
                }
            }
            .frame(width: proxy.size.width, height: proxy.size.height)
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .task {
            guard !started else { return }
            started = true
            await loadCatalog()
        }
        .task(id: selectedChannel?.pid) {
            guard let channel = selectedChannel else { return }
            await loadGuide(channel)
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onChange(of: scenePhase) { _, phase in
            UIApplication.shared.isIdleTimerDisabled = phase == .active
        }
        .onDisappear {
            autoHideTask?.cancel()
            UIApplication.shared.isIdleTimerDisabled = false
        }
    }

    @MainActor
    private func loadCatalog() async {
        catalogError = nil
        do {
            let loaded = try ChannelCatalog.loadBundled()
            channels = loaded

            let savedPid = UserDefaults.standard.string(forKey: Self.lastPidKey)
            let initial = loaded.first { $0.pid == savedPid }
                ?? loaded.first { $0.pid == Self.defaultPid }
                ?? loaded.first
            guard let initial else {
                catalogError = "App 内没有可用频道"
                return
            }

            selectedChannel = initial
            playback.play(initial)
        } catch {
            catalogError = error.localizedDescription
        }
    }

    @MainActor
    private func loadGuide(_ channel: Channel) async {
        guidePrograms = []
        guideState = .loading
        do {
            let programs = try await EpgRepository.shared.load(
                pid: channel.pid,
                date: EpgDate.todayKey()
            )
            guard !Task.isCancelled, selectedChannel?.pid == channel.pid else { return }
            guidePrograms = programs
            guideState = programs.isEmpty ? .empty : .loaded
        } catch is CancellationError {
            return
        } catch {
            guard !Task.isCancelled, selectedChannel?.pid == channel.pid else { return }
            guideState = .failed(error.localizedDescription)
        }
    }

    @MainActor
    private func selectChannel(_ channel: Channel) {
        selectedChannel = channel
        UserDefaults.standard.set(channel.pid, forKey: Self.lastPidKey)
        playback.errorMessage = nil
        playback.play(channel)
        resetAutoHide()
    }

    private func retryGuide() {
        guard let channel = selectedChannel else { return }
        Task { await loadGuide(channel) }
    }

    private func openDrawer() {
        withAnimation(.easeOut(duration: 0.18)) {
            drawerVisible = true
        }
        drawerRevision += 1
        resetAutoHide()
    }

    private func closeDrawer() {
        autoHideTask?.cancel()
        autoHideTask = nil
        withAnimation(.easeIn(duration: 0.16)) {
            drawerVisible = false
        }
    }

    private func resetAutoHide() {
        autoHideTask?.cancel()
        guard drawerVisible else { return }
        autoHideTask = Task {
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation(.easeIn(duration: 0.16)) {
                    drawerVisible = false
                }
            }
        }
    }
}

private enum GuideState: Equatable {
    case idle
    case loading
    case loaded
    case empty
    case failed(String)
}

private struct DrawerMetrics {
    let fullWidth: CGFloat
    let channelWidth: CGFloat

    init(size: CGSize) {
        let width = max(size.width, 1)
        let fractionWidth = width * 0.8
        let maxPanelWidth: CGFloat = 820
        let minVideoWidth: CGFloat = 200
        fullWidth = width < 600
            ? width
            : min(fractionWidth, maxPanelWidth, max(width - minVideoWidth, 1))

        let minGuideWidth = min(168, width / 3)
        let maxChannelWidth = max(fullWidth - minGuideWidth - 1, 1)
        let target = fullWidth * 0.34
        let floor = min(188, maxChannelWidth)
        let ceiling = min(300, maxChannelWidth)
        channelWidth = min(max(target, floor), ceiling)
    }
}

private struct ChannelPane: View {
    let channels: [Channel]
    let selectedChannel: Channel?
    let revision: Int
    let onSelect: (Channel) -> Void

    private let rowHeight: CGFloat = 48

    var body: some View {
        GeometryReader { proxy in
            ScrollViewReader { reader in
                ScrollView(.vertical, showsIndicators: false) {
                    LazyVStack(spacing: 0) {
                        Color.clear
                            .frame(height: max((proxy.size.height - rowHeight) / 2 - 8, 0))

                        ForEach(channels) { channel in
                            Button {
                                onSelect(channel)
                            } label: {
                                Text(channel.channelName)
                                    .font(.system(size: 16))
                                    .foregroundStyle(Color.white)
                                    .lineLimit(1)
                                    .frame(maxWidth: .infinity, minHeight: rowHeight, alignment: .leading)
                                    .padding(.horizontal, 20)
                                    .background(
                                        channel.id == selectedChannel?.id
                                            ? AndroidTheme.channelSelected
                                            : Color.clear
                                    )
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .id(channel.id)
                            .accessibilityLabel(channel.channelName)
                        }

                        Color.clear
                            .frame(height: max((proxy.size.height - rowHeight) / 2 - 8, 0))
                    }
                }
                .padding(.vertical, 8)
                .onAppear {
                    scrollToSelection(reader, proxy: proxy)
                }
                .onChange(of: revision) { _, _ in
                    scrollToSelection(reader, proxy: proxy)
                }
                .onChange(of: selectedChannel?.id) { _, _ in
                    scrollToSelection(reader, proxy: proxy)
                }
            }
        }
        .background(AndroidTheme.drawerBackground)
    }

    private func scrollToSelection(_ reader: ScrollViewProxy, proxy: GeometryProxy) {
        guard let selectedChannel else { return }
        DispatchQueue.main.async {
            withAnimation(.easeOut(duration: 0.18)) {
                reader.scrollTo(selectedChannel.id, anchor: .center)
            }
        }
    }
}

private struct GuidePane: View {
    let channel: Channel?
    let programs: [EpgProgram]
    let state: GuideState
    let onRetry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            VStack(alignment: .leading, spacing: 3) {
                Text("节目单 · 今天")
                    .font(.system(size: 13))
                    .foregroundStyle(AndroidTheme.guideAccent)
                Text(guideSubtitle)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(Color.white)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14)
            .padding(.top, 12)
            .padding(.bottom, 10)

            switch state {
            case .idle, .loading:
                guideMessage("正在加载节目单…")
            case .empty:
                guideMessage("今天暂无节目单")
            case .failed(let message):
                Button(action: onRetry) {
                    Text("节目单加载失败\n点按重试")
                        .font(.system(size: 14))
                        .foregroundStyle(AndroidTheme.secondaryText)
                        .multilineTextAlignment(.center)
                        .accessibilityHint(message)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 24)
                }
                .buttonStyle(.plain)
            case .loaded:
                programList
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .background(AndroidTheme.guideBackground)
    }

    private var programList: some View {
        ScrollViewReader { reader in
            ScrollView(.vertical, showsIndicators: false) {
                LazyVStack(spacing: 0) {
                    ForEach(programs) { program in
                        let start = program.startLabel.isEmpty ? "--:--" : program.startLabel
                        let end = program.endLabel.isEmpty ? "--:--" : program.endLabel
                        Text("\(program.isCurrent ? "● " : "")\(start)–\(end)  \(program.title)")
                            .font(.system(size: 14, weight: program.isCurrent ? .bold : .regular))
                            .foregroundStyle(
                                program.isCurrent
                                    ? AndroidTheme.guideAccent
                                    : AndroidTheme.statusText
                            )
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .frame(maxWidth: .infinity, minHeight: 42, alignment: .leading)
                            .padding(.horizontal, 8)
                            .background(
                                program.isCurrent
                                    ? AndroidTheme.channelSelected
                                    : Color.clear
                            )
                            .id(program.id)

                        Rectangle()
                            .fill(Color.white.opacity(0.09))
                            .frame(height: 1)
                    }
                }
            }
            .padding(.top, 6)
            .onAppear {
                scrollToCurrent(reader)
            }
            .onChange(of: programs.count) { _, _ in
                scrollToCurrent(reader)
            }
        }
    }

    private func guideMessage(_ message: String) -> some View {
        Text(message)
            .font(.system(size: 14))
            .foregroundStyle(AndroidTheme.secondaryText)
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 18)
            .padding(.top, 24)
    }

    private var guideSubtitle: String {
        let name = channel?.channelName ?? "节目单"
        return "\(name) · \(EpgDate.todayLabel())"
    }

    private func scrollToCurrent(_ reader: ScrollViewProxy) {
        guard let current = programs.first(where: \.isCurrent) else { return }
        DispatchQueue.main.async {
            reader.scrollTo(current.id, anchor: .center)
        }
    }
}

private struct LoadingOverlay: View {
    let message: String

    var body: some View {
        ZStack {
            AndroidTheme.loadingBackground.ignoresSafeArea()
            VStack(spacing: 16) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(AndroidTheme.progress)
                    .scaleEffect(1.05)
                Text(message)
                    .font(.system(size: 14))
                    .foregroundStyle(AndroidTheme.statusText)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .padding(.horizontal, 24)
            }
        }
        .allowsHitTesting(false)
    }
}

private struct BufferingBadge: View {
    let message: String

    var body: some View {
        VStack {
            HStack(spacing: 9) {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(AndroidTheme.progress)
                    .scaleEffect(0.8)
                Text(message)
                    .font(.system(size: 13, weight: .medium))
                    .foregroundStyle(AndroidTheme.statusText)
                    .lineLimit(1)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(Color.black.opacity(0.68))
            .clipShape(Capsule())
            .padding(.top, 18)

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .allowsHitTesting(false)
    }
}

private struct ErrorOverlay: View {
    let title: String
    let message: String
    let primaryTitle: String
    let primaryAction: () -> Void
    let secondaryTitle: String?
    let secondaryAction: (() -> Void)?

    var body: some View {
        ZStack {
            Color.black.opacity(0.92).ignoresSafeArea()
            VStack(spacing: 14) {
                Text(title)
                    .font(.system(size: 18, weight: .bold))
                    .foregroundStyle(AndroidTheme.statusText)
                Text(message)
                    .font(.system(size: 14))
                    .foregroundStyle(AndroidTheme.secondaryText)
                    .multilineTextAlignment(.center)
                    .lineLimit(4)

                HStack(spacing: 12) {
                    Button(primaryTitle, action: primaryAction)
                        .buttonStyle(AndroidButtonStyle())
                    if let secondaryTitle, let secondaryAction {
                        Button(secondaryTitle, action: secondaryAction)
                            .buttonStyle(AndroidButtonStyle())
                    }
                }
                .padding(.top, 4)
            }
            .padding(24)
            .frame(maxWidth: 420)
        }
    }
}

private struct AndroidButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 14, weight: .semibold))
            .foregroundStyle(Color.white)
            .padding(.horizontal, 18)
            .padding(.vertical, 10)
            .background(
                configuration.isPressed
                    ? AndroidTheme.channelFocus
                    : AndroidTheme.channelSelected
            )
            .clipShape(Capsule())
    }
}

struct PlayerLayerView: UIViewRepresentable {
    let player: AVPlayer

    func makeUIView(context: Context) -> PlayerSurfaceView {
        let view = PlayerSurfaceView()
        view.backgroundColor = .black
        view.playerLayer.videoGravity = .resizeAspect
        view.playerLayer.player = player
        return view
    }

    func updateUIView(_ uiView: PlayerSurfaceView, context: Context) {
        if uiView.playerLayer.player !== player {
            uiView.playerLayer.player = player
        }
    }
}

final class PlayerSurfaceView: UIView {
    override class var layerClass: AnyClass {
        AVPlayerLayer.self
    }

    var playerLayer: AVPlayerLayer {
        layer as! AVPlayerLayer
    }
}

struct AuthWebViewContainer: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView {
        webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

private enum AndroidTheme {
    static let loadingBackground = Color(red: 11 / 255, green: 11 / 255, blue: 11 / 255)
    static let drawerBackground = Color(red: 11 / 255, green: 11 / 255, blue: 11 / 255).opacity(0.95)
    static let guideBackground = Color(red: 22 / 255, green: 22 / 255, blue: 22 / 255).opacity(0.95)
    static let progress = Color(red: 66 / 255, green: 165 / 255, blue: 245 / 255)
    static let statusText = Color(red: 232 / 255, green: 242 / 255, blue: 1)
    static let secondaryText = Color(red: 176 / 255, green: 190 / 255, blue: 197 / 255)
    static let guideAccent = Color(red: 144 / 255, green: 202 / 255, blue: 249 / 255)
    static let channelSelected = Color(red: 13 / 255, green: 71 / 255, blue: 161 / 255)
    static let channelFocus = Color(red: 30 / 255, green: 136 / 255, blue: 229 / 255)
}
