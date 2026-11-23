import Foundation

enum ChannelGroup: String, CaseIterable, Identifiable {
    case cctv
    case satellite

    var id: String { rawValue }
}

struct Channel: Identifiable, Decodable, Hashable {
    let channelName: String
    let pid: String
    let channelType: String
    let payType: String?
    let isVip: Bool?
    let isLimitedFree: Bool?
    let is4K: Bool?

    var id: String { pid }

    var group: ChannelGroup {
        channelType == "yangshi" ? .cctv : .satellite
    }

    var isFree: Bool {
        !(isVip ?? false) && payType != "880"
    }

    var badge: String? {
        if isLimitedFree == true {
            return L10n.t("channel.limited_free")
        }
        if is4K == true {
            return "4K"
        }
        return nil
    }
}

enum ChannelCatalog {
    static func loadBundled() throws -> [Channel] {
        guard let url = Bundle.main.url(forResource: "channels", withExtension: "json") else {
            throw NSError(
                domain: "YspLive",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: L10n.t("catalog.missing_file")]
            )
        }
        let data = try Data(contentsOf: url)
        return try JSONDecoder()
            .decode([Channel].self, from: data)
            .filter { $0.isFree }
    }
}
