import SwiftUI

@main
struct YspLiveApp: App {
    @StateObject private var playback = PlaybackController()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(playback)
                .preferredColorScheme(.dark)
        }
    }
}
