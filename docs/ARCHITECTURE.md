# 架构与执行边界

## 1. 目标

在 Android TV 和 Android 手机上播放央视频 TV 页面内的央视/卫视直播，同时满足：

- 不把最终播放做成 WebView 页面；
- 页面改签名、改 token 时，尽量不跟着改原生播放器；
- TV 有可用遥控器焦点，手机有全屏/后台播放；
- 能解释并实现普通 Android 应用可用的前台服务和开机自启；
- 对“最高优先级”不作不存在的承诺。

## 2. 分层

```text
┌──────────────────────── 共享 Rust Core ────────────────────────┐
│ ChannelCatalog / ChannelKind / pid 校验                         │
│ BridgeEvent / PlayUrl 结构校验 / URL 租约 / 日志脱敏接口        │
│ EPG URL 构造 / 缓存/重试的扩展点                                │
└──────────────────────────────┬──────────────────────────────────┘
                               │
        ┌──────────────────────┴──────────────────────┐
        │                                             │
┌───────▼──────────────────────┐          ┌───────────▼─────────────────┐
│ Windows 桌面原型             │          │ Android 宿主（Kotlin）       │
│ tao + wry(WebView2)          │          │ PhoneMainActivity           │
│ 初始化脚本拦截 get_live_info │          │ TvMainActivity              │
│ mpv 子进程 / 全屏原生播放    │          │ Compose 不强制；当前用 View │
└──────────────────────────────┘          │ libmpv JNI + SurfaceView   │
                                          │ 隐藏 WebView 鉴权桥        │
                                          │ mediaPlayback 前台服务     │
                                          └─────────────────────────────┘
```

## 3. 为什么桌面用 mpv，Android 用 libmpv

### 桌面

Windows/Linux 桌面已经有成熟的 mpv/libmpv 分发方式，直接使用 `--fullscreen`、硬件解码和 HLS 重连配置最省时间，适合作为鉴权链路和码流兼容性的 PoC。

### Android

- 本项目按指定方案，将隐藏 WebView 只用于官方鉴权，最终播放交给 libmpv JNI；
- `PlaybackService` 持有 libmpv core，Activity 把 `SurfaceView` 的 `Surface` 交给它渲染；
- 预编译原生库来自 `libmpv-kotlin-multiplatform` v0.3.0（mpv 0.41、FFmpeg 9.0.1），采用 GPL-3.0-or-later 的 Android 构建；
- `arm64-v8a` 单 ABI 调试包实测约 32.1 MB，release 约 31.6 MB；arm64 AAB 约 15.2 MB；
- 多 ABI universal APK 会把 `x86_64` 也打进去，实测约 68.1 MB，因此发布时优先按 ABI 拆分或使用 AAB；
- libmpv 并不自动保证 4K/8K/AVS3/HDR 硬解优于系统 MediaCodec，必须真机逐路验收。

Media3/ExoPlayer 仍是可替代的原生后端，但当前工程已经移除 Media3 依赖，默认路径是 libmpv。不要把“用原生播放”误解为“必须用浏览器 MSE”。

## 4. 运行序列

```text
用户选台
  │
  ▼
MainActivity 保存 pid，pause 旧原生播放器
  │
  ▼
AuthWebViewBridge.load(pid)
  │  官方页面 + 官方播放器 JS/WASM
  │  XHR/fetch: /v1/player/get_live_info
  ▼
auth_bridge.js 捕获 data.playurl，只回传 HLS URL 和公开元数据
  │
  ▼
Rust/Kotlin 校验：https + host + .m3u8 + 非空
  │
  ▼
libmpv JNI 原生播放（Android）/ mpv 子进程（Windows）
  │
  ├─ 成功后暂停官方 <video>，避免重复下载
  ├─ 失败：URL 过期/403/断网 → 重新加载官方页面鉴权
  └─ 不支持码流/DRM → 显示错误或回退官方 WebView（产品化再做）
```

## 5. 桥接实现细节

- `assets/auth_bridge.js` 同时支持：
  - `window.ipc.postMessage`（wry）；
  - `window.YspAndroidBridge.postMessage`（Android `addJavascriptInterface`）；
  - `window.chrome.webview.postMessage`（原生 WebView2 备用）。
- 只匹配 `/v1/player/get_live_info`，不读取 `video.currentSrc`。后者通常是 `blob:`，不能交给原生播放器。
- XHR 和 fetch 都包装；XHR 只在目标 URL 上 `addEventListener("load")`，fetch 只 clone 目标响应。
- 页面 JS 必须早于官方播放器脚本执行；Android 优先使用 `WebViewCompat.addDocumentStartJavaScript`，旧 WebView 在 `onPageStarted` 回退注入，并记录兼容性风险。
- `window.ipc` 只允许 `yangshipin.cn`/子域页面发消息；宿主再次校验消息类型和 HLS 结构，不能信任页面内容。
- URL 不进日志、不进通知、不写 SharedPreferences；只在内存中从桥传到播放器。
- 拿到 URL 后 JS 将官方视频静音并暂停；WebView 保留用于后续重新鉴权。

## 6. TV/手机 UI

- TV：`TvMainActivity` 强制横屏，使用 `LEANBACK_LAUNCHER`，频道列表和按钮都可被 D-pad 聚焦；目标是把焦点限制在少量控件内。
- 手机：`PhoneMainActivity` 使用 `LAUNCHER`，同样支持全屏和后台播放，但不申请 TV 的 HOME 入口。
- 两个入口共享 `BaseLiveActivity`，避免播放、鉴权和自启逻辑分叉。
- 当前 UI 使用 Android View 而不是 Compose，减少依赖和首版构建风险；后续可以在不改变 Rust/桥协议的情况下替换为 Compose for TV。

## 7. 进程与生命周期

```text
MainActivity ──bind──► PlaybackService (foreground, mediaPlayback)
     │                         │
     │                         ├─ libmpv core + SurfaceView Surface
     │                         └─ Notification + PendingIntent
     │
     └─ AuthWebViewBridge (1x1 attached WebView)
              └─ HTTP(S) official page + player JS
```

- Activity `onStop` 解绑播放服务但不强制停止播放；通知提供停止入口。
- 服务在前台运行时持有 libmpv core；当前使用普通媒体通知，未接入 MediaSession。
- Activity 重新进入时，如果服务正在播放同一个 `pid`，直接复用；否则重新鉴权。
- WebView 出错、播放器 403/5xx 或 URL 过期时，重新加载官方页面，让官方播放器重新生成 URL。

## 8. 频道目录

`assets/channels.json` 是按 2026-11-19 页面快照生成的种子，当前 64 路：

- 央视 40 路；
- 卫视 24 路；
- 字段包括 `channelName`、`pid`、`streamId`、`channelType`、`payType`、`isVip`、`is4K`。

正式版本应从官方页面或授权 API 刷新，不要依赖这份快照的长期有效性。播放鉴权仍以官方 WebView 为准。

## 9. 安全边界

- WebView 导航白名单：`yangshipin.cn`/子域、`cctv.com`/子域；其他链接拦截。
- PlayURL 白名单：HTTPS、主机非空、路径以 `.m3u8` 结尾。
- 不保存完整播放 URL；不把 token/query 写入日志或崩溃上报。
- 不开启 WebView 文件访问、内容访问和外部窗口。
- 不尝试解密 DRM，不绕过付费、地区、广告或会员限制。
- 上游内容授权和 mpv/FFmpeg 许可证审查是发布前置条件。

