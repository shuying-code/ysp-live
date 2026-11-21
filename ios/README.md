# YSP Live iOS

这是 `ysp-live` 的 iPhone / iPad 原生入口。iOS 端不使用 libmpv：

- 界面与 Android 手机版保持一致：横屏全屏播放，点按画面打开左侧频道列和右侧节目单；
- 启动时自动播放上次选择的频道，首次启动默认播放天津卫视；
- 频道切换、节目单日期和 5 秒自动收起行为与 Android 版一致；
- 隐藏的 `WKWebView` 加载官方 `yangshipin.cn` 播放页；
- 注入现有 `assets/auth_bridge.js`，通过 `WKScriptMessageHandler` 接收 `live_info`；
- 收到官方生成的 HTTPS HLS 地址后，交给系统 `AVPlayer` 播放；
- 官方页内播放器会被桥脚本静音，避免双音轨。

## 构建

需要 macOS + Xcode + XcodeGen：

```bash
cd ios
brew install xcodegen
xcodegen generate
open YspLive.xcodeproj
```

## Windows + 云构建 + 侧载

1. 将 `ios/` 和 `.github/workflows/ios.yml` 推送到 GitHub。
2. 手动运行 Actions 里的 `iOS Build`。
3. 下载 `YspLive-unsigned.ipa`。
4. 在 Windows 安装苹果官网 Web 版 iTunes 64 位，再用 Sideloadly 通过 Apple ID 签名，
   USB-C 连接 iPhone Air 安装。
5. 首次运行前在 iPhone 设置里打开“开发者模式”并重启。

免费 Apple ID 的签名通常 7 天后过期；每次更新 IPA 需要重新签名安装。

## 当前限制

- 已实现官方 `get_live_info.backurl_list` 备用线路、低清晰度、原生/官方卡顿看门狗和重新鉴权。`f0c931a` 已通过 GitHub Actions 构建并侧载到 iPhone Air；后续提交以对应 Actions 产物为准，不能用旧 IPA 的结果代替新版本验收。
- `f0c931a` 安装版在 CCTV9、海南卫视、山东卫视的 120 秒复测中均通过直接 DVT 截图确认出画；尚未在该版本上重跑全部 51 路，因此不能写成 51/51 全量通过。
- 已起播后的缓冲只显示顶部小徽标，不再用全屏“缓冲中…”遮挡画面；该界面修复仍需在新 IPA 上复测。
- 音画同步当前依赖 AVPlayer 单音轨路径和系统音视频同步，尚未完成仪器级唇音偏差测量。
- 央视网网页备选页默认关闭；当前 WebView 没有可用的 Widevine 解码路径，不能把它当作真实备线。
- 官方页面和取流接口可能变化，属于上游风险。
- 该项目没有节目内容再分发授权，不应作为 App Store 商业发行版本。
