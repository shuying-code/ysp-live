import Foundation

enum L10n {
    static var locale: Locale {
        let identifier = Bundle.main.preferredLocalizations.first ?? "en"
        return Locale(identifier: identifier)
    }

    static func t(_ key: String, _ arguments: CVarArg...) -> String {
        let format = NSLocalizedString(key, comment: "")
        guard !arguments.isEmpty else { return format }
        return String(format: format, locale: locale, arguments: arguments)
    }

    static func qualityLabel(_ raw: String) -> String {
        let replacements = [
            ("超高清", "quality.ultra_hd"),
            ("蓝光", "quality.blu_ray"),
            ("超清", "quality.super_hd"),
            ("高清", "quality.hd"),
            ("标清", "quality.sd"),
            ("当前清晰度", "quality.current")
        ]
        return replacements.reduce(raw) { value, replacement in
            value.replacingOccurrences(
                of: replacement.0,
                with: t(replacement.1)
            )
        }
    }
}
