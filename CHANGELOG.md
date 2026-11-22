# Changelog

本项目采用语义化版本。Release 产物与对应 commit 以 GitHub Releases 为准。

## [0.1.0] - 2026-11-22

首个可下载预览版。

### 新增

- Android 手机与 Android TV 双入口：隐藏 WebView 官方鉴权、libmpv 原生播放和官方页内播放回退。
- 频道抽屉、当天 EPG、D-pad 导航、快速切台和 5 秒自动收起。
- Android `mediaPlayback` 前台服务、后台播放、通知停止入口和最佳努力开机自启。
- 可选 `kiosk` 变体，提供 HOME activity-alias，不静默修改系统默认桌面。
- iPhone / iPad 原生入口：SwiftUI、隐藏 WKWebView、AVPlayer 播放和 EPG。
- Rust `ysp-core`：频道模型、URL 校验、桥协议解析、URL 租约和 EPG URL。
- Windows 隐藏 WebView2 + mpv 原型，用于验证鉴权链路和码流兼容性。
- 首批 Release 产物：Android arm64 调试签名预览 APK、iOS 未签名 IPA、SHA-256 校验文件。

### 修复

- 原生播放接管后暂停官方播放器，避免双音轨和双份流量。
- 切台时隔离旧取流回调，减少旧频道状态覆盖新频道。
- 对已知不兼容码流保留官方播放器回退，不强行原生解码。
- iOS 改善前后台恢复、备用线路、卡顿看门狗和隐藏鉴权页面。

### 已知限制

- Android 原生白名单目前只有已真机验证的天津卫视；其余频道默认走官方播放器。
- Android TV、开机自启、Kiosk 和多厂商 ROM 仍需扩大设备矩阵验证。
- 4K/8K、HDR、AVS3 和 VIP 频道未做完整验收。
- iOS 未签名 IPA 需要自行签名安装；免费 Apple ID 的签名通常 7 天过期。
- 项目没有内容再分发授权，不应作为 App Store 商业发行版或公开内容分发服务。
