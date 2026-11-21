import Foundation

struct EpgProgram: Identifiable, Equatable {
    let id: String
    let title: String
    let startTime: Int64
    let endTime: Int64
    let startLabel: String
    let endLabel: String

    var isCurrent: Bool {
        let now = Int64(Date().timeIntervalSince1970)
        return now >= startTime && now < endTime
    }
}

enum EpgError: LocalizedError {
    case invalidURL
    case invalidResponse
    case httpStatus(Int)
    case malformedData

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "节目单地址无效"
        case .invalidResponse:
            return "节目单响应无效"
        case .httpStatus(let status):
            return "节目单请求失败：HTTP \(status)"
        case .malformedData:
            return "节目单数据解析失败"
        }
    }
}

actor EpgRepository {
    static let shared = EpgRepository()

    private let userAgent =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    private var cache: [String: [EpgProgram]] = [:]

    func load(pid: String, date: String) async throws -> [EpgProgram] {
        let cacheKey = "\(pid)/\(date)"
        if let cached = cache[cacheKey] {
            return cached
        }
        guard let url = URL(string: "https://capi.yangshipin.cn/api/yspepg/program/\(pid)/\(date)") else {
            throw EpgError.invalidURL
        }

        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        request.setValue("https://yangshipin.cn/", forHTTPHeaderField: "Referer")
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("application/octet-stream", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw EpgError.invalidResponse
        }
        guard (200...299).contains(http.statusCode) else {
            throw EpgError.httpStatus(http.statusCode)
        }

        let programs = try EpgParser.parse([UInt8](data))
        cache[cacheKey] = programs
        return programs
    }
}

enum EpgDate {
    static func todayKey() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = "yyyyMMdd"
        return formatter.string(from: Date())
    }

    static func todayLabel() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "zh_CN")
        formatter.timeZone = TimeZone(identifier: "Asia/Shanghai")
        formatter.dateFormat = "M月d日 EEEE"
        return formatter.string(from: Date())
    }
}

private enum EpgParser {
    static func parse(_ bytes: [UInt8]) throws -> [EpgProgram] {
        var reader = ProtoReader(bytes: bytes)
        var programs: [EpgProgram] = []

        while reader.hasMore {
            let key = try reader.readVarint()
            let tag = key >> 3
            let wire = UInt8(key & 0x07)
            if tag == 2 && wire == 2 {
                let payload = try reader.readBytes()
                if let program = try? parseProgram(payload) {
                    programs.append(program)
                }
            } else {
                try reader.skip(wire: wire)
            }
        }

        return programs.sorted { $0.startTime < $1.startTime }
    }

    private static func parseProgram(_ bytes: [UInt8]) throws -> EpgProgram {
        var reader = ProtoReader(bytes: bytes)
        var id = ""
        var title = ""
        var startTime: Int64 = 0
        var endTime: Int64 = 0
        var startLabel = ""
        var endLabel = ""

        while reader.hasMore {
            let key = try reader.readVarint()
            let tag = key >> 3
            let wire = UInt8(key & 0x07)
            switch (tag, wire) {
            case (1, 2):
                id = try reader.readString()
            case (2, 2):
                title = try reader.readString()
            case (3, 0):
                startTime = Int64(clamping: try reader.readVarint())
            case (4, 0):
                endTime = Int64(clamping: try reader.readVarint())
            case (5, 2):
                startLabel = try reader.readString()
            case (6, 2):
                endLabel = try reader.readString()
            default:
                try reader.skip(wire: wire)
            }
        }

        guard !title.isEmpty, startTime > 0, endTime > startTime else {
            throw EpgError.malformedData
        }
        return EpgProgram(
            id: id.isEmpty ? "\(startTime)-\(title)" : id,
            title: title,
            startTime: startTime,
            endTime: endTime,
            startLabel: startLabel,
            endLabel: endLabel
        )
    }
}

private struct ProtoReader {
    let bytes: [UInt8]
    private(set) var position = 0

    var hasMore: Bool {
        position < bytes.count
    }

    mutating func readVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while shift < 64 {
            guard position < bytes.count else {
                throw EpgError.malformedData
            }
            let byte = bytes[position]
            position += 1
            result |= UInt64(byte & 0x7F) << shift
            if byte & 0x80 == 0 {
                return result
            }
            shift += 7
        }
        throw EpgError.malformedData
    }

    mutating func readBytes() throws -> [UInt8] {
        let length = try readVarint()
        guard length <= UInt64(Int.max), length <= UInt64(bytes.count - position) else {
            throw EpgError.malformedData
        }
        let end = position + Int(length)
        let result = Array(bytes[position..<end])
        position = end
        return result
    }

    mutating func readString() throws -> String {
        String(decoding: try readBytes(), as: UTF8.self)
    }

    mutating func skip(wire: UInt8) throws {
        switch wire {
        case 0:
            _ = try readVarint()
        case 1:
            try advance(8)
        case 2:
            let length = try readVarint()
            guard length <= UInt64(Int.max) else {
                throw EpgError.malformedData
            }
            try advance(Int(length))
        case 5:
            try advance(4)
        default:
            throw EpgError.malformedData
        }
    }

    private mutating func advance(_ length: Int) throws {
        guard length >= 0, length <= bytes.count - position else {
            throw EpgError.malformedData
        }
        position += length
    }
}
