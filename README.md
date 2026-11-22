# YSP Live

<p align="center">
  <img src="ios/YspLive/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png" width="112" alt="YSP Live">
</p>

<p align="center">
  <b>Android TV · Android 手机 · iPhone / iPad 直播客户端</b><br>
  隐藏 WebView 完成官方鉴权，原生播放器负责已验证码流，官方页内播放兜底。
</p>

<p align="center">
  <a href="https://github.com/shuying-code/ysp-live/actions/workflows/android.yml"><img alt="Android Build" src="https://github.com/shuying-code/ysp-live/actions/workflows/android.yml/badge.svg"></a>
  <a href="https://github.com/shuying-code/ysp-live/actions/workflows/ios.yml"><img alt="iOS Build" src="https://github.com/shuying-code/ysp-live/actions/workflows/ios.yml/badge.svg"></a>
  <a href="https://github.com/shuying-code/ysp-live/releases/latest"><img alt="Release" src="https://img.shields.io/github/v/release/shuying-code/ysp-live?color=blue"></a>
  <img alt="Android" src="https://img.shields.io/badge/Android-6.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="iOS" src="https://img.shields.io/badge/iOS-17.0%2B-000000?logo=apple&logoColor=white">
  <img alt="Rust" src="https://img.shields.io/badge/Rust-1.85%2B-orange?logo=rust">
  <img alt="License" src="https://img.shields.io/badge/license-MIT-green">
</p>

<p align="center">
  <a href="#下载">下载</a> ·
  <a href="#功能">功能</a> ·
  <a href="#语言与模块">语言与模块</a> ·
  <a href="#架构">架构</a> ·
  <a href="#本地构建">本地构建</a> ·
  <a href="#已知限制">已知限制</a> ·
  <a href="#问题反馈">问题反馈</a>
</p>

> **项目状态（2026-11-22）**：Android 手机已在 Redmi K30S Ultra（Android 12 / arm64）完成真机播放、切台、EPG、CCTV 备选页和原生 libmpv 验证；iOS 已通过 GitHub Actions 构建未签名 IPA，并在 iPhone Air 上验证部分频道。Android TV、开机自启、Kiosk、4K/8K/HDR/AVS3 和全部频道仍需扩大验证。本文不把“能构建”写成“全部设备已通过”。

> **合规声明**：项目只做播放器与官方页面之间的技术适配，不提供内容源、不绕过 DRM/付费/会员/地区限制，也没有央视、央视频或卫视的内容再分发授权。公开发布或商业使用前请自行取得授权并完成第三方许可证审查。

---

## 下载

前往 [最新 Release](https://github.com/shuying-code/ysp-live/releases/latest)：

| 平台 | 架构 | 产物 | 说明 |
|---|---|---|---|
| Android TV / 手机 | arm64-v8a | [ysp-live-android-arm64.apk](https://github.com/shuying-code/ysp-live/releases/latest/download/ysp-live-android-arm64.apk) | Android 6.0+；**调试签名预览包**，可直接侧载，不是商店正式签名 |
| iPhone / iPad | arm64 | [ysp-live-ios-unsigned.ipa](https://github.com/shuying-code/ysp-live/releases/latest/download/ysp-live-ios-unsigned.ipa) | iOS 17+；**未签名 IPA**，需 Sideloadly/AltStore/Xcode 自行签名 |
| 校验文件 | — | [SHA256SUMS.txt](https://github.com/shuying-code/ysp-live/releases/latest/download/SHA256SUMS.txt) | 下载后建议先校验 |
| 全部版本 | — | [Releases 页面](https://github.com/shuying-code/ysp-live/releases) | 历史版本与更新说明 |

> **Android**：当前 APK 使用调试证书签名，适合预览和真机验证；不同的调试证书之间不能直接覆盖安装，正式分发需要自行配置发布签名。
>
> **iOS**：未签名 IPA 不含 Apple 开发者证书。免费 Apple ID 签名通常 7 天后过期，重新签名前请保留配置。Sideloadly 侧载步骤见 [ios/SIDELOAD_WINDOWS.md](ios/SIDELOAD_WINDOWS.md)。
>
> **Windows**：`ysp-desktop` 是隐藏 WebView2 + mpv 的可执行原型，不是本次 Release 的终端用户安装包；构建方式见下文。

---

## 功能

- **央视 / 卫视直播入口**：内置 64 路频道种子（40 路央视、24 路卫视），应用过滤 VIP/付费档后展示 51 路免费频道。
- **不伪造官方鉴权**：隐藏 WebView 加载央视频官方页面，让官方 JavaScript 自己完成 `cKey`、`signature`、`token`、`ysign` 等易变逻辑。
- **原生播放 + 官方回退**：已验证频道把 `get_live_info` 的 HLS 地址交给 libmpv/AVPlayer/mpv；其他频道保留官方页内播放器，不把未验证码流强行交给原生解码。
- **Android 双形态**：手机使用 `LAUNCHER` + `TextureView`，电视使用 `LEANBACK_LAUNCHER` + `SurfaceView`；共享同一套频道、鉴权和播放逻辑。
- **电视遥控器体验**：横屏全屏，D-pad 唤起频道列表，上下切台，右键进入节目单，左键返回，5 秒无操作自动收起。
- **当天 EPG**：频道抽屉内显示当天节目单，当前节目高亮定位；节目单接口返回 protobuf，由宿主原生解析并缓存。
- **连续切台与恢复**：官方页面内调用 `changeTV()` 切台；切台时暂停旧播放器、隔离旧回调；播放失败或播放 URL 过期时重新鉴权。
- **CCTV 备选线路**：主流 CCTV 频道在主源 10 秒未起播或明确失败时，可切换央视网直播页作为备选；其他频道不受影响。
- **后台与常驻播放**：Android 使用 `mediaPlayback` 前台服务与媒体通知；iOS 开启后台音频模式，并由 AVPlayer 管理音频会话。
- **可选 Kiosk**：`kiosk` 变体增加 HOME activity-alias，适合专用电视/默认桌面场景；普通安装包不会静默修改系统设置。
- **最小化敏感数据**：播放 URL 只保存在进程内；日志、通知不输出完整 query；不写 SharedPreferences，不做崩溃上报。
- **iOS 原生入口**：SwiftUI 界面、隐藏 WKWebView 鉴权、AVPlayer 播放、EPG 与前后台恢复；不打包 libmpv。

> 原生播放并不自动等于“所有码流更兼容”。CCTV 和部分卫视的 TS/H.264 在桌面 mpv 软件解码时也会出现绿紫块，说明问题可能在上游码流。项目遇到已知异常时优先回退官方播放器，而不是用破解或解密掩盖问题。

---

## 语言与模块

这是刻意采用的多语言架构：**平台能力交给平台语言，共享规则交给 Rust，页面内适配交给 JavaScript**。

| 语言 | 主要模块 | 负责什么 | 为什么这样选 |
|---|---|---|---|
| **Rust** | `crates/ysp-core` | 频道模型、PID/日期校验、HLS URL 校验、桥消息解析、租约、EPG URL | 纯逻辑可测试、可复用；桌面与 Android JNI 共享一套规则，避免 Kotlin/Swift 各写一份后漂移 |
| **Rust** | `crates/ysp-desktop` | 隐藏 WebView2、初始化脚本、IPC、mpv 进程与全屏播放 | 桌面窗口、WebView 和外部播放器控制都能在 Rust 内完成，适合做鉴权链路 PoC |
| **Rust** | `crates/ysp-android-jni` | 把核心能力暴露为 `libysp_core_android.so` | JNI 面保持极薄；动态库缺失时 Kotlin 仍有回退路径 |
| **Kotlin** | `android/app/src/main/java` | 手机/TV 入口、WebView 鉴权、libmpv 播放服务、D-pad、EPG、前台服务、自启、Kiosk | Android 生命周期、Surface、WebView、JNI 和前台服务都是平台 API，直接用 Kotlin 最稳 |
| **Swift** | `ios/YspLive` | SwiftUI、WKWebView、AVPlayer、EPG、前后台恢复 | 直接使用 Apple 原生 UI/WebKit/AVFoundation；同时避免为 iOS 交叉编译 libmpv |
| **JavaScript** | `assets/auth_bridge.js` | 拦截 XHR/fetch、转发 HLS、暂停官方媒体、切台/回退辅助函数 | 只有注入官方页面上下文的脚本才能可靠读取播放器请求；保持零框架、可审查 |
| **Kotlin DSL / PowerShell / YAML** | `android/*.kts`、`scripts/`、`.github/workflows/` | 构建、原生库校验、JNI 编译、CI/Release | 构建胶水层需要可重复执行，不进入产品业务逻辑 |

### 一句话理解边界

```text
官方网页 JavaScript：只做官方鉴权与页面内播放
        │  JSON 桥消息（HLS 地址、清晰度、状态）
        ▼
Rust core：校验频道、URL、消息、日期与 URL 租约
        │
        ├── Kotlin：Android WebView / 前台服务 / libmpv / D-pad
        ├── Swift：iOS WKWebView / AVPlayer / SwiftUI
        └── Rust：Windows WebView2 / mpv 原型
```

- **为什么不全用 Rust**：仍然要分别调用 Android WebView、Surface、前台服务和 iOS WKWebView/AVPlayer/UIKit；自建跨平台 UI 只会把平台胶水藏得更深。
- **为什么不全用 Kotlin/Swift**：两端无法共享业务代码，频道、URL、桥协议和 EPG 规则会分裂。
- **为什么 iOS 不链接 Rust**：当前可共享规则很少，AVPlayer 已覆盖播放需求；引入 libmpv 反而增加体积、构建链和 GPL 分发复杂度。
- **为什么 JavaScript 不实现业务**：页面脚本可能随上游更新失效；鉴权依赖官方页面，但频道、播放器生命周期和错误恢复必须留在宿主。

完整的模块职责、依赖方向、取舍和后续演进见 **[docs/LANGUAGE_AND_MODULES.md](docs/LANGUAGE_AND_MODULES.md)**。

---

## 架构

```text
用户选台
  │
  ▼
宿主保存 pid，停止/暂停旧播放器
  │
  ▼
隐藏 WebView 加载 https://yangshipin.cn/tv/home?pid=...
  │  官方页面与官方播放器 JavaScript
  │  XHR/fetch: /v1/player/get_live_info
  ▼
auth_bridge.js 捕获 playurl，只发送 HLS 地址与公开元数据
  │
  ▼
Rust/Kotlin/Swift 再次校验：HTTPS、host、.m3u8、非空
  │
  ├─ 已验证原生频道 ──► libmpv（Android）/ AVPlayer（iOS）/ mpv（Windows）
  │     ├─ 取流后暂停官方 <video>/<audio> 与 WebView
  │     ├─ 403 / URL 过期 / 断网 ──► 重新加载官方页鉴权
  │     └─ 已知码流异常 ──► 不再强行解码，回退官方播放器
  │
  └─ 其他频道 ──► 官方 WebView 播放器兼容回退
```

关键约束：

- 不抓包后写死某个 `cKey`/`token`/`ysign`；
- 不把 `blob:` 地址交给原生播放器；
- 原生模式稳定后只保留一条音轨；
- WebView 导航白名单限制为 `yangshipin.cn`、`cctv.com`、`cctv.cn`、`cntv.cn` 及其子域；
- 不对网页输入盲信，宿主侧再次校验消息和 URL。

更完整的分层、生命周期和安全边界见 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**。

---

## 平台支持

| 平台 | 入口 | 播放后端 | 当前状态 |
|---|---|---|---|
| Android 手机 | `PhoneMainActivity` / LAUNCHER | 隐藏 WebView + libmpv / 官方播放器 | ✅ 已在 Redmi K30S Ultra 真机验证 |
| Android TV | `TvMainActivity` / LEANBACK_LAUNCHER | 隐藏 WebView + libmpv / 官方播放器 | 🧪 代码与构建完成，TV 真机与 D-pad 仍需矩阵验收 |
| Android Kiosk | `kiosk` 变体 + HOME alias | 同 Android | 🧪 可选变体，需专用设备默认桌面验证 |
| iPhone / iPad | SwiftUI + WKWebView | AVPlayer / 官方播放器 | ✅ GitHub Actions 可构建；部分频道已在 iPhone Air 验证，未做 51/51 全量 |
| Windows x64 | `ysp-desktop` | WebView2 + 外部 mpv | 🧪 开发原型，需要本机安装 mpv |

---

## 本地构建

### 1. Rust 核心测试

```powershell
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
cargo test -p ysp-core
```

### 2. Windows 原型

需要 WebView2 Runtime 和 mpv。`YSP_MPV` 可用于指定 mpv 路径：

```powershell
$env:YSP_MPV = 'C:\path\to\mpv.exe'
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
powershell -ExecutionPolicy Bypass -File .\scripts\run-desktop.ps1 -Channel CCTV1 -Visible -RunSeconds 30
```

去掉 `-Visible` 即为实际隐藏鉴权模式。也可以直接：

```powershell
cargo run -p ysp-desktop -- --channel CCTV1
cargo run -p ysp-desktop -- --pid 600001859
cargo run -p ysp-desktop -- --list-channels
```

日志：`%LOCALAPPDATA%\ysp-live\ysp-live.log`。日志只记录 HLS 主机和结果，不记录完整播放 URL。

### 3. Android

环境变量示例：

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_NDK_HOME = 'C:\Android\Sdk\ndk\26.1.10909125'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
```

先下载并校验 libmpv/FFmpeg 原生库，再编译 Rust JNI：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-android-natives.ps1 -Abi arm64-v8a
powershell -ExecutionPolicy Bypass -File .\scripts\build-android-rust.ps1 -Abi arm64-v8a
```

构建 arm64 手机/TV APK：

```powershell
Push-Location android
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=arm64-v8a
Pop-Location
```

产物：`android\app\build\outputs\apk\standard\debug\app-standard-debug.apk`

Kiosk/默认桌面变体：

```powershell
Push-Location android
.\gradlew.bat :app:assembleKioskRelease -PyspAbi=arm64-v8a
Pop-Location
```

可用 ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。Release 默认只提供 arm64 预览包；其他 ABI 请按需构建并真机验证。

### 4. iOS

需要 macOS + Xcode + XcodeGen：

```bash
cd ios
brew install xcodegen
xcodegen generate
open YspLive.xcodeproj
```

云构建工作流：`.github/workflows/ios.yml`，产物为未签名 `YspLive-unsigned.ipa`。Windows 侧载步骤见 [ios/SIDELOAD_WINDOWS.md](ios/SIDELOAD_WINDOWS.md)。

### 5. 全部 Rust 静态检查

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check.ps1
```

---

## 项目结构

```text
ysp-live/
├─ crates/
│  ├─ ysp-core/                 频道、桥协议、URL 校验、EPG 与单元测试
│  ├─ ysp-desktop/              Windows 隐藏 WebView2 + mpv 原型
│  └─ ysp-android-jni/          Android JNI 门面
├─ android/                     Kotlin Android 手机/TV 宿主，standard/kiosk 双变体
├─ ios/                         SwiftUI + WKWebView + AVPlayer 原生入口
├─ assets/
│  ├─ channels.json             64 路频道种子
│  └─ auth_bridge.js            官方页面内 XHR/fetch 拦截与桥接
├─ docs/                        架构、语言边界、自启、体积、测试、合规、反馈
├─ scripts/                     构建、运行、原生库下载与检查
├─ .github/
│  ├─ ISSUE_TEMPLATE/           Bug、功能建议、使用咨询模板
│  ├─ workflows/                iOS 构建与后续 CI
│  └─ PULL_REQUEST_TEMPLATE.md
├─ CHANGELOG.md
├─ CONTRIBUTING.md
├─ SECURITY.md
└─ CODE_OF_CONDUCT.md
```

`android/app/src/main/jniLibs` 中的 `.so` 默认被 `.gitignore` 忽略，重新 clone 后需执行原生库下载脚本。

---

## 关键文档

- [语言与功能模块（为什么用 Rust / Kotlin / Swift / JavaScript）](docs/LANGUAGE_AND_MODULES.md)
- [架构与执行边界](docs/ARCHITECTURE.md)
- [执行计划](docs/EXECUTION_PLAN.md)
- [Android 开机自启与“最高优先级”](docs/ANDROID_AUTOSTART_AND_PRIORITY.md)
- [桥协议](docs/BRIDGE_PROTOCOL.md)
- [打包体积对比](docs/PACKAGING_AND_SIZES.md)
- [测试计划](docs/TEST_PLAN.md)
- [使用反馈指南](docs/FEEDBACK.md)
- [合规与内容授权](docs/COMPLIANCE.md)
- [原始可行性 / 平台选型 / 体积方案](docs/FEASIBILITY_AND_SIZES.md)
- [Android libmpv 实现与体积](docs/LIBMPV_ANDROID.md)
- [第三方许可证与分发边界](docs/THIRD_PARTY_NOTICES.md)
- [iOS 构建与侧载](ios/README.md)

---

## 已验收与已知限制

### 已验收范围

- Android：Redmi K30S Ultra（`M2007J3SC`，Android 12，arm64）安装 `standardDebug`；CCTV1/CCTV5、北京卫视、江苏卫视官方播放已实测；CCTV1 央视网备选页已实测全屏出画；天津卫视已实测 libmpv MediaCodec 硬解。
- Android 交互：频道切换、页内快速切台、重新鉴权、连续快切、官方与原生之间切换均已验证。
- iOS：GitHub Actions 构建未签名 IPA，并已通过 Sideloadly 在 iPhone Air 上安装；部分频道完成 120 秒出画复测，不能用旧 IPA 的结果代替新版本。
- Release：Android arm64 预览 APK、iOS 未签名 IPA 与 SHA-256 校验文件。

### 当前限制

- Android 原生白名单目前只有天津卫视；其余 23 路卫视未逐路验证，默认走官方播放器。
- CCTV 和部分卫视原生播放会出现上游 H.264 损坏；回退官方播放器可正常出画和出声，不要通过伪造签名、解密或绕过保护来“修复”。
- Android TV、Surface 输出、D-pad、多厂商 ROM/WebView、开机自启和 Kiosk 仍需分别验收。
- 4K/8K、HDR、AVS3、VIP 频道必须逐路验证；遇到不支持的码流应回退官方 WebView。
- 频道种子是 2026-11-19 页面快照，正式版本应从官方页面或授权 API 刷新。
- 节目单当前只显示当天；多日浏览和定时刷新尚未实现。
- 当前未接入 `MediaSession`；电视媒体键、锁屏媒体控制和系统媒体面板尚未完成验收。
- iOS 央视网网页备选页默认关闭；当前 WKWebView 没有可用 Widevine 解码路径，不能当作真实备线。
- iOS 未做完整 51 路回归、仪器级唇音同步测量和 App Store 分发验证。
- Windows 原型需要本机 mpv，不是零依赖安装包。

### 体积参考

2026-11-19 的 arm64 构建记录（字节，release APK 未签名）：

| 产物 | 字节数 | 约 |
|---|---:|---:|
| `standard` arm64 debug | 32,119,583 | 32.1 MB |
| `kiosk` arm64 debug | 32,120,410 | 32.1 MB |
| `standard` arm64 release unsigned | 31,587,300 | 31.6 MB |
| `standard` arm64 AAB | 15,203,349 | 15.2 MB |
| `standard` universal debug（arm64 + x86_64） | 68,120,196 | 68.1 MB |
| Rust JNI `libysp_core_android.so` arm64 | 574,208 | 0.57 MB |

---

## 安全与合规

- 项目不实现 DRM 解密、付费绕过、去广告、地区绕过或接口签名伪造。
- 完整播放 URL、token、Cookie 等敏感信息不写日志、不写通知、不持久化。
- Android APK 内置的 libmpv/FFmpeg 原生库是 GPL-3.0-or-later 构建；分发 APK 前必须满足对应许可证义务。iOS 包不包含 libmpv。
- 本项目没有节目内容分发授权；商业发布前必须取得央视/央视频及相应卫视的内容授权，并审查 mpv/FFmpeg 的许可证与专利问题。
- 安全问题请按 [SECURITY.md](SECURITY.md) 私下上报，不要公开披露。

详见 [docs/COMPLIANCE.md](docs/COMPLIANCE.md) 与 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md)。

---

## 问题反馈

遇到问题或有建议，请先阅读 **[使用反馈指南](docs/FEEDBACK.md)**，再通过 GitHub Issues 提交：

- 🐞 [Bug / 播放问题](https://github.com/shuying-code/ysp-live/issues/new?template=bug_report.yml)
- 💡 [功能建议](https://github.com/shuying-code/ysp-live/issues/new?template=feature_request.yml)
- ❓ [使用咨询](https://github.com/shuying-code/ysp-live/issues/new?template=question.yml)
- 🔐 [私下报告安全漏洞](https://github.com/shuying-code/ysp-live/security/advisories/new)

提交前请移除完整 HLS 地址、token、Cookie、Apple ID 和设备标识。播放问题请写明平台、设备、系统、频道、原生/官方路径以及是否必现。维护者会尽力处理，但项目没有商业 SLA。

---

## 许可

代码采用 [MIT License](LICENSE)。第三方组件、预编译原生库和节目内容的授权边界分别见 [docs/THIRD_PARTY_NOTICES.md](docs/THIRD_PARTY_NOTICES.md) 与 [docs/COMPLIANCE.md](docs/COMPLIANCE.md)。

© 2026 shuying-code
