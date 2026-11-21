# YSP Live iOS

这是 `ysp-live` 的 iPhone / iPad 原生入口。iOS 端不使用 libmpv：

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

- 只覆盖 YSP 官方页可鉴权、可直接播放的 HLS 频道；CCTV 备选页还不能像 Android 那样自动切换。
- 官方页面和取流接口可能变化，属于上游风险。
- 该项目没有节目内容再分发授权，不应作为 App Store 商业发行版本。
