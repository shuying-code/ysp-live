# 贡献指南（Contributing to YSP Live）

感谢你愿意为 **YSP Live** 贡献力量。提交前请先阅读本指南，并遵守 [行为准则](CODE_OF_CONDUCT.md)。

## 项目边界

本项目用于验证“官方 WebView 鉴权 + 原生播放/官方回退”的直播播放方案。以下改动不会被接受：

- 复刻或伪造官方鉴权签名、绕过 DRM、付费、会员或地区限制；
- 去广告、破解播放器或分发未授权内容；
- 把完整播放地址、token、Cookie 写入日志、通知或持久化配置；
- 为规避上游码流问题而引入不可审计的二进制补丁。

发现安全问题时不要公开提交，请按 [SECURITY.md](SECURITY.md) 私下上报。

## 环境要求

- Rust stable（仓库当前 `rust-version = 1.85`）；
- Android：JDK 17、Android SDK 34、NDK `26.1.10909125`；
- iOS：macOS、Xcode、XcodeGen；
- Windows 原型：WebView2 Runtime、mpv；
- 建议使用 `cargo fmt` 和 `cargo clippy` 保持 Rust 代码风格一致。

## 本地检查

### Rust 核心

```powershell
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
cargo test -p ysp-core
cargo check -p ysp-desktop
```

### Android

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_NDK_HOME = 'C:\Android\Sdk\ndk\26.1.10909125'
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-android-natives.ps1 -Abi arm64-v8a
powershell -ExecutionPolicy Bypass -File .\scripts\build-android-rust.ps1 -Abi arm64-v8a
Push-Location android
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=arm64-v8a
Pop-Location
```

### iOS

```bash
cd ios
brew install xcodegen
xcodegen generate
xcodebuild \
  -project YspLive.xcodeproj \
  -scheme YspLive \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  build
```

### 全部静态检查

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check.ps1
```

## 代码风格

- Rust 使用 `rustfmt` 默认风格；Android 使用 Kotlin 官方风格；Swift 使用 Xcode 默认格式。
- 文本文件统一 UTF-8 + LF。
- 新增共享逻辑优先放 `ysp-core`，并补单元测试。
- JNI 方法保持最小面；平台能力留在 Kotlin/Swift。
- `auth_bridge.js` 不引入构建步骤、框架或第三方 CDN，保持可注入、可审查。
- 涉及官方页面结构、播放器字段或原生库的改动，必须在 PR 中说明验证设备和验证结果。

## 提交信息

使用语义化前缀，标题简洁、使用祈使句：

- `feat:` 新功能
- `fix:` 缺陷修复
- `docs:` 文档
- `refactor:` 重构
- `perf:` 性能优化
- `test:` 测试
- `ci:` 构建 / 工作流
- `chore:` 杂项

示例：`fix(android): keep native surface during channel switch`

## 分支与 Pull Request

1. 从 `main` 创建 `feat/xxx` 或 `fix/xxx` 分支。
2. 保持提交聚焦，一个 PR 解决一个问题。
3. PR 描述中写明：变更动机、影响平台、验证方式、已知风险。
4. UI 或播放相关改动请附截图/录屏；无法覆盖的设备请明确写出。
5. 合并前至少通过相关测试或 CI。

## 文档

- 用户可见行为变化更新 `README.md` 和 `CHANGELOG.md`；
- 架构或语言边界变化更新 `docs/ARCHITECTURE.md` 和 `docs/LANGUAGE_AND_MODULES.md`；
- 新平台、新依赖和新分发方式需要更新合规/许可证说明。

## 问题反馈

- 提交 Issue 前阅读 [docs/FEEDBACK.md](docs/FEEDBACK.md)。
- 可复现 Bug 使用 Bug 模板；功能建议、使用咨询使用对应模板。
- 提交日志前务必脱敏完整播放地址、token、Cookie 和个人信息。
