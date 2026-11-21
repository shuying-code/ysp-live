import AVKit
import Foundation
import SwiftUI
import WebKit

struct RootView: View {
    @EnvironmentObject private var playback: PlaybackController
    @State private var channels: [Channel] = []
    @State private var loadError: String?
    @State private var query = ""
    @State private var selectedGroup: ChannelGroup?

    private let columns = [
        GridItem(.flexible(minimum: 140), spacing: 12),
        GridItem(.flexible(minimum: 140), spacing: 12)
    ]

    private var filteredChannels: [Channel] {
        channels.filter { channel in
            let matchesGroup = selectedGroup == nil || channel.group == selectedGroup
            let matchesQuery = query.isEmpty ||
                channel.channelName.localizedCaseInsensitiveContains(query)
            return matchesGroup && matchesQuery
        }
    }

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.035, green: 0.07, blue: 0.09),
                    Color(red: 0.06, green: 0.12, blue: 0.13),
                    Color(red: 0.02, green: 0.035, blue: 0.05)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    header
                    statusStrip
                    searchBar
                    groupPicker

                    if let loadError {
                        errorCard(loadError)
                    } else if channels.isEmpty {
                        HStack(spacing: 12) {
                            ProgressView().tint(Theme.accent)
                            Text("正在读取频道目录…")
                                .font(.subheadline)
                                .foregroundStyle(Theme.muted)
                        }
                        .padding(18)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Theme.card)
                        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                    } else {
                        LazyVGrid(columns: columns, spacing: 12) {
                            ForEach(filteredChannels) { channel in
                                channelCard(channel)
                            }
                        }
                    }

                    Text("内容版权归原平台所有。iOS 端仅供个人设备测试，不包含内容再分发授权。")
                        .font(.caption2)
                        .foregroundStyle(Theme.muted)
                        .padding(.top, 4)
                }
                .padding(.horizontal, 18)
                .padding(.top, 12)
                .padding(.bottom, 28)
            }

            AuthWebViewContainer(webView: playback.webView)
                .frame(width: 1, height: 1)
                .opacity(0.01)
                .allowsHitTesting(false)
        }
        .task {
            guard channels.isEmpty else { return }
            do {
                channels = try ChannelCatalog.loadBundled()
            } catch {
                loadError = error.localizedDescription
            }
        }
        .fullScreenCover(isPresented: $playback.isFullScreen) {
            PlayerScreen()
                .environmentObject(playback)
        }
        .alert(
            "播放提示",
            isPresented: Binding(
                get: { playback.errorMessage != nil },
                set: { if !$0 { playback.errorMessage = nil } }
            )
        ) {
            Button("好", role: .cancel) {
                playback.errorMessage = nil
            }
        } message: {
            Text(playback.errorMessage ?? "")
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Circle()
                    .fill(Theme.live)
                    .frame(width: 8, height: 8)
                Text("LIVE · IPHONE / IPAD")
                    .font(.caption.weight(.bold))
                    .tracking(1.4)
                    .foregroundStyle(Theme.live)
            }
            Text("YSP LIVE")
                .font(.system(size: 38, weight: .black, design: .rounded))
                .foregroundStyle(.white)
            Text("\(channels.count) 个免费频道 · 官方页鉴权 · 系统 AVPlayer")
                .font(.subheadline)
                .foregroundStyle(Theme.muted)
        }
    }

    private var statusStrip: some View {
        HStack(spacing: 10) {
            if playback.isPreparing {
                ProgressView()
                    .tint(Theme.accent)
            } else {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .foregroundStyle(Theme.accent)
            }
            Text(playback.status)
                .font(.footnote.weight(.semibold))
                .foregroundStyle(.white.opacity(0.9))
            Spacer()
            if let channel = playback.currentChannel {
                Text(channel.channelName)
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Theme.accent)
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var searchBar: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(Theme.muted)
            TextField("搜索频道", text: $query)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundStyle(.white)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(Theme.muted)
                }
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var groupPicker: some View {
        HStack(spacing: 9) {
            GroupChip(title: "全部", selected: selectedGroup == nil) {
                selectedGroup = nil
            }
            ForEach(ChannelGroup.allCases) { group in
                GroupChip(title: group.rawValue, selected: selectedGroup == group) {
                    selectedGroup = group
                }
            }
        }
    }

    private func channelCard(_ channel: Channel) -> some View {
        Button {
            playback.play(channel)
        } label: {
            VStack(alignment: .leading, spacing: 11) {
                HStack {
                    Text(channel.group.rawValue)
                        .font(.caption2.weight(.bold))
                        .foregroundStyle(Theme.accent)
                    Spacer()
                    if let badge = channel.badge {
                        Text(badge)
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background(Theme.accent.opacity(0.16))
                            .foregroundStyle(Theme.accent)
                            .clipShape(Capsule())
                    }
                }

                Text(channel.channelName)
                    .font(.headline.weight(.bold))
                    .foregroundStyle(.white)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)

                HStack(spacing: 6) {
                    Image(systemName: "play.fill")
                        .font(.caption2)
                    Text("播放")
                        .font(.caption.weight(.semibold))
                }
                .foregroundStyle(
                    playback.currentChannel?.id == channel.id ? Theme.live : Theme.muted
                )
            }
            .padding(14)
            .frame(minHeight: 128, alignment: .top)
            .background(
                playback.currentChannel?.id == channel.id
                    ? Theme.cardActive
                    : Theme.card
            )
            .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .stroke(
                        playback.currentChannel?.id == channel.id
                            ? Theme.accent.opacity(0.55)
                            : Color.white.opacity(0.05),
                        lineWidth: 1
                    )
            )
        }
        .buttonStyle(.plain)
    }

    private func errorCard(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("频道目录加载失败", systemImage: "exclamationmark.triangle.fill")
                .font(.headline)
                .foregroundStyle(Theme.live)
            Text(message)
                .font(.footnote)
                .foregroundStyle(Theme.muted)
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Theme.card)
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

struct GroupChip: View {
    let title: String
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.caption.weight(.bold))
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(selected ? Theme.accent : Theme.card)
                .foregroundStyle(selected ? Color.black : Theme.muted)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

struct AuthWebViewContainer: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView {
        webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

struct PlayerScreen: View {
    @EnvironmentObject private var playback: PlaybackController
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if let player = playback.player {
                VideoPlayer(player: player)
                    .ignoresSafeArea()
            }

            if playback.isPreparing {
                VStack(spacing: 14) {
                    ProgressView()
                        .scaleEffect(1.2)
                        .tint(.white)
                    Text(playback.status)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(.white.opacity(0.9))
                        .multilineTextAlignment(.center)
                }
                .padding(24)
                .background(.black.opacity(0.58))
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
            }

            if let message = playback.errorMessage {
                VStack(spacing: 14) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .font(.title2)
                        .foregroundStyle(Theme.live)
                    Text(message)
                        .font(.subheadline)
                        .foregroundStyle(.white)
                        .multilineTextAlignment(.center)
                    Button("重新连接") {
                        playback.reload()
                    }
                    .font(.subheadline.weight(.bold))
                    .padding(.horizontal, 18)
                    .padding(.vertical, 9)
                    .background(Theme.accent)
                    .foregroundStyle(.black)
                    .clipShape(Capsule())

                    if let pid = playback.currentChannel?.pid,
                       let page = URL(string: "https://yangshipin.cn/tv/home?pid=\(pid)") {
                        Link("在 Safari 打开", destination: page)
                            .font(.footnote.weight(.semibold))
                            .foregroundStyle(.white.opacity(0.82))
                    }
                }
                .padding(22)
                .frame(maxWidth: 340)
                .background(.black.opacity(0.72))
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .padding(24)
            }

            VStack {
                HStack(spacing: 12) {
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.headline)
                            .frame(width: 38, height: 38)
                            .background(.black.opacity(0.5))
                            .foregroundStyle(.white)
                            .clipShape(Circle())
                    }

                    VStack(alignment: .leading, spacing: 2) {
                        Text(playback.currentChannel?.channelName ?? "直播")
                            .font(.headline.weight(.bold))
                            .foregroundStyle(.white)
                        Text(playback.status)
                            .font(.caption)
                            .foregroundStyle(.white.opacity(0.7))
                    }

                    Spacer()

                    Button {
                        playback.reload()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .font(.headline)
                            .frame(width: 38, height: 38)
                            .background(.black.opacity(0.5))
                            .foregroundStyle(.white)
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)

                Spacer()

                Text("系统 AVPlayer · HLS")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white.opacity(0.55))
                    .padding(.bottom, 16)
            }
        }
        .statusBarHidden(true)
        .onDisappear {
            playback.stop()
        }
    }
}

private enum Theme {
    static let accent = Color(red: 0.98, green: 0.75, blue: 0.32)
    static let live = Color(red: 0.95, green: 0.30, blue: 0.24)
    static let muted = Color.white.opacity(0.58)
    static let card = Color.white.opacity(0.065)
    static let cardActive = Color(red: 0.98, green: 0.75, blue: 0.32).opacity(0.10)
}
