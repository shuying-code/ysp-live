# YSP Live

隐藏 WebView 负责官方鉴权；已在真机验证可解码的频道交给原生播放器，其余频道使用官方页内播放做兼容回退。目标平台是 Android TV 和 Android 手机，同时提供一个 Windows 隐藏 WebView + mpv 可执行原型。

> 项目状态：2026-11-20。已在 Redmi K30S Ultra（`M2007J3SC`，Android 12，arm64）上安装 `standardDebug` 并完成真机播放验收。CCTV1/CCTV5、北京卫视和江苏卫视已实测官方播放；CCTV1 央视网备选页已实测全屏出画；天津卫视已实测 libmpv MediaCodec 硬解。频道切换、页内快速切台、重新鉴权和连续快切均已验证。Android TV、开机自启和 4K/8K/HDR/AVS3 仍未验收。

## 结论先说清楚

- **不是所有频道都能直接原生播放。** CCTV 和部分卫视的 TS/H.264 码流在桌面 mpv 软件解码时也出现同样的绿紫块或解码错误，说明问题在上游码流，不在手机 GPU、TextureView 或 MediaCodec 初始化。
- **不建议纯 Rust 复刻官方鉴权签名。** `cKey`、`signature`、`token`、`ysign` 都会变化，复刻成本和失效风险都高。
- **推荐混合架构：** 隐藏 WebView 加载官方页面，让官方 JS 自己完成鉴权；已验证的频道把 `get_live_info` 的 `playurl` 交给 libmpv，其他频道保留官方页内播放器作为兼容路径。
- **Windows 原型使用 mpv 进程；Android 使用 libmpv JNI。** 手机使用 `TextureView`，Android TV 使用 `SurfaceView`。当前只有天津卫视进入原生白名单，避免其他频道落入可复现的绿块路径。
- **原生模式不能让隐藏 WebView 继续出声。** 取流完成后会暂停官方 `<video>/<audio>` 并暂停整个 WebView，稳定状态只保留一条原生音频轨。
- **Android 没有“普通 App 设置最高优先级”的官方接口。** 前台 `mediaPlayback` 服务是正常应用能获得的最高常驻级别；真正的开机直达需要专用 TV 的默认桌面/Device Owner/Kiosk，见 `docs/ANDROID_AUTOSTART_AND_PRIORITY.md`。

## 项目结构

```text
ysp-live/
  Cargo.toml                         Rust workspace
  crates/ysp-core/                   频道、桥协议、URL 校验、EPG
  crates/ysp-desktop/                Windows 隐藏 WebView2 + mpv 原型
  crates/ysp-android-jni/            Android 频道/URL 校验 JNI 适配层
  assets/channels.json               当前抓取到的 64 路频道种子（40 央视 + 24 卫视）
  assets/auth_bridge.js              XHR/fetch 拦截与 IPC 注入脚本
  android/                           Kotlin Android TV/手机宿主，单应用双入口
    app/src/main/jniLibs/             libmpv/FFmpeg 预编译原生库（arm64-v8a、x86_64）
    app/src/main/                    标准变体：LAUNCHER + LEANBACK_LAUNCHER
    app/src/kiosk/                   Kiosk 变体：额外 HOME activity-alias
  docs/                              架构、执行计划、自启/优先级、打包体积、测试、合规
  scripts/                           构建、运行、检查和原生库下载脚本
```

## 已实现

- 频道种子 64 路：40 路央视、24 路卫视；应用过滤 VIP/付费档后展示 51 路免费频道。
- `ysp-core`：频道目录、HLS URL 校验、桥消息解析、URL 租约过期判断、EPG URL 构造和单元测试。
- Windows 桌面原型：
  - `tao` + `wry` 创建窗口；
  - 默认隐藏 WebView2，加载官方 TV 页；
  - 注入 `assets/auth_bridge.js`；
  - 捕获 `get_live_info` 后启动 `mpv` 全屏播放；
  - 启动参数包含 `Referer`，不打印完整播放 URL。
- Android 工程：
  - `PhoneMainActivity`（LAUNCHER）和 `TvMainActivity`（LEANBACK_LAUNCHER）；
  - 隐藏 WebView 鉴权桥；兼容频道可铺满屏幕使用官方播放器；
  - libmpv JNI HLS 播放；手机 `TextureView`、TV `SurfaceView`；
  - 原生白名单与官方兼容回退，避免已知异常码流直接显示绿紫块；
  - 官方页首次加载后使用页面内 `changeTV` 切台，不再为每次换台重载整个播放器；
  - 已实测普通切台、连续快切、官方/原生之间切换和重新鉴权；
  - 播放界面不显示底栏或停止按钮；单击画面或按电视确认键唤起频道列表，当前频道高亮并居中，选台后保留频道界面，5 秒无操作自动回到直播；
  - 抽屉展开时第一次点直播画面只收起节目单，第二次点画面收起频道栏，再次点击恢复完整抽屉；
  - 频道抽屉内置当天节目单：当前节目高亮定位，方向键换频道时自动刷新，电视右方向键进入节目单浏览、左方向键返回；
  - 主流 CCTV 频道在主源 10 秒未起播或明确失败时，自动改用央视网直播页作为备选线路；其他频道不受影响；
  - 预编译 libmpv/FFmpeg 原生库按 ABI 打入 APK；
  - `mediaPlayback` 前台服务、普通媒体通知和停止操作（MediaSession 尚未接入）；
  - `BootReceiver` 最佳努力自启；
  - `kiosk` 变体提供可选 HOME activity-alias。
- Rust JNI 构建脚本：把 `ysp-core` 编译成 `libysp_core_android.so`；没有 `.so` 时 Kotlin 仍有校验回退路径。

## 快速开始

### 1. 核心测试

```powershell
cd 'F:\codex工作区\ysp-live'
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
cargo test -p ysp-core
```

### 2. Windows 原型

需要 mpv。可以先使用已有的 bundled mpv：

```powershell
$env:YSP_MPV = 'F:\codex工作区\wallpaper-rs\bundled-mpv\mpv.exe'
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
powershell -ExecutionPolicy Bypass -File .\scripts\run-desktop.ps1 -Channel CCTV1 -Visible -RunSeconds 30
```

`-Visible` 只用于观察 WebView 鉴权；实际使用去掉它。默认频道是天津卫视（`pid=600152137`），也可以使用：

```powershell
cargo run -p ysp-desktop -- --channel CCTV1
cargo run -p ysp-desktop -- --pid 600001859
cargo run -p ysp-desktop -- --list-channels
```

日志位置：`%LOCALAPPDATA%\ysp-live\ysp-live.log`。日志只记录 HLS 主机和结果，不记录完整播放 URL。

### 3. Android 构建

环境变量示例：

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_NDK_HOME = 'C:\Android\Sdk\ndk\26.1.10909125'
$env:JAVA_HOME = 'D:\app\jdk-17.0.20.8-hotspot'
```

本仓库当前已把 libmpv 预编译原生库放入 `android/app/src/main/jniLibs`，但 `.gitignore` 不会提交 `.so`。如果重新 clone 或清理了目录，先按 ABI 下载并校验上游原生库：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\fetch-android-natives.ps1 -Abi arm64-v8a
```

脚本使用 `libmpv-kotlin-multiplatform` v0.3.0 的发布资产并校验 SHA-256；可用 ABI 为 `arm64-v8a`、`armeabi-v7a`、`x86_64`、`x86`。默认构建只启用 arm64 和 x86_64，老电视的 `armeabi-v7a` 需要单独下载并实测。然后构建 Rust JNI：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\build-android-rust.ps1 -Abi arm64-v8a
```

然后构建 arm64 APK（推荐，体积最小）：

```powershell
cd android
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=arm64-v8a
```

产物：

```text
android\app\build\outputs\apk\standard\debug\app-standard-debug.apk
```

Kiosk/默认桌面变体：

```powershell
.\gradlew.bat :app:assembleKioskRelease -PyspAbi=arm64-v8a
```

`kiosk` 只增加 HOME activity-alias；是否把它设为默认桌面由用户在系统设置里确认，普通安装包不会静默篡改系统设置。

### 4. 全部静态检查

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\check.ps1
```

## 关键文档

- [架构与执行边界](docs/ARCHITECTURE.md)
- [执行计划](docs/EXECUTION_PLAN.md)
- [Android 开机自启与“最高优先级”](docs/ANDROID_AUTOSTART_AND_PRIORITY.md)
- [桥协议](docs/BRIDGE_PROTOCOL.md)
- [打包体积对比](docs/PACKAGING_AND_SIZES.md)
- [测试计划](docs/TEST_PLAN.md)
- [合规与内容授权](docs/COMPLIANCE.md)
- [原始可行性/平台选型/体积方案](docs/FEASIBILITY_AND_SIZES.md)
- [Android libmpv 实现与体积](docs/LIBMPV_ANDROID.md)
- [第三方许可证与分发边界](docs/THIRD_PARTY_NOTICES.md)

## 设计约束

- 官方页面和播放接口不是公开稳定 API，可能随时变化。不要把某个抓包 URL、`cKey`、`token`、`ysign` 写死。
- 原生白名单频道中，WebView 只负责鉴权；拿到 `playurl` 后暂停官方媒体和 WebView，避免双倍下载与双音轨。官方兼容频道则由 WebView 显示和播放官方页面。
- 不把“提高某个 H.264 解码器兼容性”当作修复上游损坏码流的手段。无法可靠原生解码时回退官方播放器，不做 DRM 解密或保护绕过。
- Android WebView 必须先注入脚本再加载页面；项目优先使用 `WebViewCompat.addDocumentStartJavaScript`，不支持时在 `onPageStarted` 回退注入。
- 播放 URL 只保存在进程内，不写入 SharedPreferences；媒体通知和日志都不能出现完整 query。
- 不实现 DRM 解密、付费绕过、去广告、地区绕过或接口签名伪造。
- 本项目没有内容分发授权。商业发布前必须取得央视/央视频及相应卫视的内容授权，并审查 mpv/FFmpeg 的许可证和专利问题。Android 预编译原生库是 GPL-3.0-or-later 构建，分发 APK 时必须满足 GPL 义务。

## 当前限制

- 真机验收范围是 Redmi K30S Ultra / Android 12 / arm64；不同厂商 ROM、Android TV、D-pad 和 WebView 版本仍需分别验收。
- 原生白名单目前只有天津卫视。其余 23 路卫视没有逐路验证，默认走官方播放器；这不是声称它们全部已测试可用。
- CCTV 和部分卫视原生播放会出现上游 H.264 损坏，回退官方播放器可正常出画和出声；不要通过伪造签名、解密或绕过保护来“修复”这些码流。
- Android 手机在 Android 10+ 上通常不能被普通应用静默拉起 Activity；`BootReceiver` 只能尽力尝试，失败时回退为前台服务通知。
- 4K/8K、HDR、AVS3 和 VIP 频道必须逐路验证；遇到不支持的码流应回退官方 WebView，而不是尝试破解。
- 频道种子是 2026-11-19 页面快照，正式版本应从官方页面/授权 API 刷新。
- 节目单当前只显示当天，直接解析央视频 protobuf 接口；多日浏览和定时刷新尚未实现。
- 当前未接入 `MediaSession`，电视媒体键、锁屏媒体控制和系统媒体面板尚未完成验收。

## 当前实测体积

2026-11-19 清理 Android 构建目录后重新构建的结果，单位为字节；release APK 当前未签名：

| 产物 | 字节数 | 约 |
|---|---:|---:|
| `standard` arm64 debug | 32,119,583 | 32.1 MB |
| `kiosk` arm64 debug | 32,120,410 | 32.1 MB |
| `standard` arm64 release unsigned | 31,587,300 | 31.6 MB |
| `kiosk` arm64 release unsigned | 31,587,972 | 31.6 MB |
| `standard` arm64 AAB | 15,203,349 | 15.2 MB |
| `standard` x86_64 debug | 38,184,752 | 38.2 MB |
| `standard` universal debug（arm64 + x86_64） | 68,120,196 | 68.1 MB |
| Rust JNI `libysp_core_android.so` arm64 | 574,208 | 0.57 MB |

当前 Gradle 日志提示原生库无法被 strip，因而 APK 中的 `.so` 仍可能带有调试符号；修复 NDK strip 后“可执行代码”体积可能略有变化，但不能把这一点当作已经完成的商店体积优化。

## 下一步

1. 在 Android TV 和更多手机 ROM 上安装 `standardDebug`，验证 D-pad、Surface、后台保活和不同 WebView 版本。
2. 按 `docs/ANDROID_AUTOSTART_AND_PRIORITY.md` 的设备矩阵验证自启；TV 专用设备再验证 `kiosk` 默认桌面。
3. 接入 `MediaSession`，补齐 TV 媒体键、锁屏媒体控制、音频焦点和来电/音频设备切换处理。
4. 增加 EPG 多日浏览与自动刷新、频道目录刷新、播放 URL 租约刷新、错误重试和远程日志脱敏。
5. 完成内容授权、GPL 对应源码提供和发行合规审查后，才考虑商业分发。

