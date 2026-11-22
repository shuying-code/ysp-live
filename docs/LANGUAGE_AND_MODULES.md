# 语言与功能模块

本文说明 `ysp-live` 为什么同时使用 Rust、Kotlin、Swift 和 JavaScript，以及每种语言各自负责什么、边界在哪里。

## 1. 选择原则

这个项目的复杂度不在页面数量，而在三件事：

1. 官方鉴权只能在官方网页的 JavaScript 环境里完成；
2. 播放器深度依赖 Android、iOS 和桌面各自的媒体/窗口 API；
3. 频道、URL、EPG 和桥协议需要跨平台保持一致，不能各写一份再慢慢漂移。

因此代码按“变化原因”分层，而不是强行塞进一种语言：

- **平台能力用平台原生语言**：Android 用 Kotlin，iOS 用 Swift。
- **共享的纯逻辑用 Rust**：频道模型、URL 校验、桥消息解析、EPG URL 等。
- **必须运行在官方页面里的逻辑用 JavaScript**：XHR/fetch 拦截、媒体暂停、桥消息发送。
- **构建和验证用脚本语言**：PowerShell、Kotlin DSL、YAML，只服务交付，不进入产品运行逻辑。

## 2. 一览表

| 语言 | 主要目录 | 负责的功能 | 为什么选它 |
|---|---|---|---|
| **Rust** | `crates/ysp-core` | 频道目录、频道类型、PID/日期校验、播放 URL 校验、桥事件解析、URL 租约、EPG URL | 纯逻辑集中在一处，桌面和 Android JNI 复用；强类型和单元测试适合处理不可信输入 |
| **Rust** | `crates/ysp-desktop` | Windows 原型：`tao` + `wry` 隐藏 WebView2，注入桥脚本，启动 `mpv` 全屏播放 | 桌面窗口、WebView 和外部进程控制都能在 Rust 内完成，适合做鉴权链路和码流兼容性验证 |
| **Rust** | `crates/ysp-android-jni` | 把 `ysp-core` 暴露为 `libysp_core_android.so` 的 JNI 门面 | JNI 面保持极薄，Android 宿主不必重复实现共享校验；失败时 Kotlin 有回退路径 |
| **Kotlin** | `android/app/src/main/java` | Android 手机/TV 双入口、WebView 鉴权、libmpv 播放服务、D-pad/频道抽屉、EPG、前台服务、自启、Kiosk | Android 生命周期、`WebView`、`TextureView`/`SurfaceView`、前台服务和 JNI 都是一等公民；直接用平台语言比再造跨平台 UI 层更稳 |
| **Swift** | `ios/YspLive` | SwiftUI 界面、隐藏 `WKWebView` 鉴权、`AVPlayer` 播放、EPG、前后台恢复 | iOS 的 UI、WebKit、AVFoundation 和音频会话只能通过 Apple 原生框架获得最完整能力；同时避免为 iOS 交叉编译 libmpv |
| **JavaScript** | `assets/auth_bridge.js` | 在官方页面上下文拦截 `get_live_info`，转发 HLS 地址，暂停官方媒体，提供切台/回退/播放状态辅助函数 | 这是唯一能可靠读到官方播放器请求的位置；脚本必须早于页面播放器执行，且不应引入框架 |
| **Kotlin DSL / PowerShell / YAML** | `android/*.kts`、`scripts/`、`.github/workflows/` | Gradle 构建、原生库下载与校验、Rust JNI 编译、iOS 云构建 | 构建步骤需要可重复、可审计；脚本语言适合胶水层，不承担产品业务逻辑 |

## 3. 为什么共享逻辑放在 Rust

`ysp-core` 处理的是“无论跑在哪个平台都必须一致”的规则：

- 从 `channels.json` 解析频道并拒绝重复 PID；
- 校验 PID 只包含数字且长度有限；
- 校验播放地址是 HTTP(S)、主机非空、路径以 `.m3u8` 结尾；
- 解析桥消息，拒绝未知类型和不完整字段；
- 生成 EPG 请求地址并校验日期格式；
- 提供 URL 租约的过期判断；
- 在 `Debug` 输出里隐藏完整路径和 query。

这些规则如果分别用 Kotlin 和 Swift 重写，最常见的问题不是“第一次写不出来”，而是后续修一处、漏两处。Rust 版本可以跑单元测试，Android 通过 JNI 复用；桌面原型也直接依赖它。

JNI 面故意只有四个能力：版本、频道数量、URL 校验、EPG URL。这样即使 Rust 动态库没有编进去，Android 仍能用 Kotlin 回退路径启动 UI 和 WebView，不会因为 FFI 层失败导致整个应用不可用。

## 4. Kotlin 负责 Android 的平台事实

Android 端不是“Rust 播放器加一个壳”，而是完整的平台宿主：

- `PhoneMainActivity` 与 `TvMainActivity` 分别提供手机启动器和 Leanback 启动器；
- `BaseLiveActivity` 统一频道切换、抽屉、节目单、播放状态和错误恢复；
- `AuthWebViewBridge` 管理隐藏 WebView、导航白名单、脚本注入和消息回调；
- `PlaybackService` 以前台 `mediaPlayback` 服务持有 libmpv core，接收 `TextureView`/`SurfaceView` 的 Surface；
- `ChannelRepository`、`EpgRepository` 负责频道种子和当天 EPG；
- `BootReceiver` 和 Kiosk 变体处理 Android 能力范围内能做到的自启与默认桌面。

这些 API 与 Android 生命周期、权限、Surface、JNI 线程模型紧密相关。用 Kotlin 可以直接使用官方 API，也方便 Gradle 构建和后续演进；如果为了“统一语言”自建一层跨平台 UI/播放器抽象，维护成本会高于收益。

## 5. Swift 负责 iOS，并刻意不使用 libmpv

iOS 端使用 SwiftUI + WKWebView + AVPlayer：

- 隐藏 `WKWebView` 加载同一个官方页面和 `auth_bridge.js`；
- 桥消息经 `WKScriptMessageHandler` 进入 `PlaybackController`；
- 收到合法 HLS 地址后交给 `AVPlayer`，由系统处理解码、音频会话和前后台切换；
- `Views.swift` 负责与 Android 手机版一致的横屏全屏界面、频道列和节目单。

iOS 不链接 `ysp-core`，也不打包 libmpv。原因是：

- AVPlayer 已覆盖目标场景，减少 IPA 体积和 GPL 分发复杂度；
- iOS 不能像 Android 那样直接复用现有预编译 libmpv 方案；
- iOS 侧需要跨平台共享的规则目前很少，用 Swift 原生模型和校验更直接。

代价是频道模型、URL 校验等少量规则目前存在 Swift 版本。若未来这部分继续增长，优先考虑共享 JSON Schema 或代码生成，而不是仅为复用而把 Rust 强行引入 iOS 构建链。

## 6. JavaScript 是“最窄的页面内适配层”

`auth_bridge.js` 不实现业务 UI，也不保存配置。它只做官方页面上下文里必须做的事：

- 包装 `XMLHttpRequest` 和 `fetch`，匹配 `/v1/player/get_live_info`；
- 只提取播放地址、清晰度、PID 和备用线路等公开字段；
- 通过 `window.ipc`、`window.YspAndroidBridge` 或 `window.webkit.messageHandlers.yspBridge` 发送 JSON；
- 在原生播放接管后静音/暂停官方 `<video>`、`<audio>`；
- 提供官方播放器回退、备用线路、视频尺寸和健康状态辅助函数；
- 使用 `changeTV()` 在官方页面内切台，减少整页重载和重新鉴权。

选择注入 JavaScript 而不是复刻签名算法，是为了让官方页面自己完成 `cKey`、`token`、`ysign` 等易变逻辑。宿主仍然会把页面视为不可信输入，并在 Rust/Kotlin/Swift 侧再次校验 URL 和消息结构。

## 7. 为什么不是单一语言

### 全部用 Rust

理论上可以做 Rust UI 和 FFI，但仍要分别调用 Android WebView、前台服务、Surface、iOS WKWebView、AVPlayer 和 UIKit/SwiftUI。结果是保留几乎全部平台胶水，还额外增加一层 Rust UI/生命周期适配。当前界面只有播放页、频道列、节目单和错误态，用平台原生 UI 更直接。

### 全部用 Kotlin 或 Swift

Android 和 iOS 无法共享 Kotlin/Swift 业务代码；频道、URL、桥协议和 EPG 规则会分裂成两份。桌面原型也会被排除在共享核心之外。`ysp-core` 用 Rust 正好覆盖这部分低 UI、强校验、可测试的逻辑。

### 全部逻辑塞进 JavaScript

官方页面脚本可能变化，WebView 也可能被系统回收。鉴权可以依赖官方 JavaScript，但频道目录、播放器生命周期、后台服务、原生解码和错误恢复不能建立在页面脚本长期稳定这一假设上。

## 8. 当前边界与演进方向

| 边界 | 当前做法 | 后续方向 |
|---|---|---|
| Rust 与 Android | 四个 JNI 方法，Kotlin 有回退 | 保持 JNI 面稳定，新增能力优先放 Kotlin；确属共享规则再进 Rust |
| Rust 与 iOS | 暂不共享 | 规则增长时考虑共享 schema/生成模型，不强推 FFI |
| Kotlin 与官方页面 | 只通过 JSON 消息交互 | 保持消息类型小、可校验、可忽略未知字段 |
| Swift 与官方页面 | 复用同一份 `auth_bridge.js` | 保持桥脚本双端兼容，避免平台分叉 |
| 播放器 | Android libmpv、iOS AVPlayer、桌面 mpv | 以真机码流验证决定原生/官方路径，不把“原生”当作必然更兼容 |

语言数量多不是目标；目标是把“平台相关的部分”和“必须一致的部分”分开。当前分层是在可维护性、平台能力、构建复杂度和分发合规之间做出的取舍。
