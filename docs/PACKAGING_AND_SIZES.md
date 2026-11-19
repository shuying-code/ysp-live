# 打包方式与体积对比

> 本页区分“本机实测”和“方案估算”。实测数字来自 2026-11-19 的当前构建；最终体积会随 ABI、Gradle、libmpv/FFmpeg 版本和是否签名而变化。

## 1. 本机实测

| 产物 | 实测字节数 | 约 |
|---|---:|---:|
| Android `standard` arm64 debug | 32,119,583 | 32.1 MB |
| Android `kiosk` arm64 debug | 32,120,410 | 32.1 MB |
| Android `standard` arm64 release unsigned | 31,587,300 | 31.6 MB |
| Android `kiosk` arm64 release unsigned | 31,587,972 | 31.6 MB |
| Android `standard` AAB arm64 | 15,203,349 | 15.2 MB |
| Android `standard` x86_64 debug | 38,184,752 | 38.2 MB |
| Android universal debug arm64 + x86_64 | 68,120,196 | 68.1 MB |
| Rust JNI `libysp_core_android.so` arm64 | 574,208 | 0.57 MB |
| Windows `ysp-desktop.exe` release | 930,816 | 0.93 MB |
| 外置 mpv.exe（本机已有构建） | 121,915,392 | 121.9 MB |

说明：

- debug APK 包含调试信息/调试签名，不能当作商店发行体积；
- release APK 当前是 unsigned，需要开发者用自己的 keystore 签名；
- AAB 由商店按 ABI 拆分，用户下载体积通常接近单 ABI 值；
- 上述 arm64 APK 已包含 `libysp_core_android.so`；
- arm64 `jniLibs` 合计约 29.8 MB，x86_64 `jniLibs` 合计约 35.9 MB；
- 当前 Gradle strip 任务提示预编译 `.so` 无法剥离，发行前应检查是否仍带调试符号；
- 安装后占用通常大于 APK，因为系统会展开原生库和 dex；本机没有 Android 设备，未实测安装后 `du`。

## 2. 方案级对比

| 方案 | 典型包体 | 结论 |
|---|---:|---|
| 纯 WebView / 系统 WebView 宿主 | 8—20 MB | 开发快，但最终画面仍在网页里，TV 硬解/遥控/后台不可控 |
| WebView 自带完整运行时 | 130—200 MB（Windows） | 不适合作为电视主方案 |
| 隐藏 WebView + Media3 原生播放 | 10—25 MB APK（估算） | 体积小、硬解成熟；需要另写播放器链路 |
| **隐藏 WebView + libmpv 原生播放（本项目）** | **32.1 MB arm64 debug；31.6 MB arm64 release；15.2 MB AAB** | 符合指定架构；体积和 GPL 成本高 |
| 纯原生授权 API + libmpv | 30—65 MB（Windows） | 只有拿到稳定授权 API 才值得 |
| Electron/CEF | 80—200 MB | 不推荐电视端 |

本项目的 31.6 MB release 不是“libmpv 只有 31 MB”的意思：其中约 29.8 MB 是 arm64 的 mpv/FFmpeg 原生库，另有 Kotlin/WebView/Rust 代码和资源。使用 AAB 可以避免让每台设备下载多个 ABI；universal APK 会同时携带 arm64 和 x86_64 两套原生库。

## 3. ABI 策略

- `arm64-v8a`：现代 Android TV、手机主目标，当前推荐发布 ABI；
- `x86_64`：主要用于模拟器，当前仓库也包含原生库；
- `armeabi-v7a`：老电视/盒子可能使用，但当前工程未打入该 ABI；需要时单独下载/验证预编译库，不能只改 `abiFilters` 就假设可运行；
- 使用 `-PyspAbi=arm64-v8a` 只构建单 ABI，避免 universal APK；
- 商店发布优先 AAB，侧载才提供按 ABI 命名的 APK。

## 4. Windows 原型

- `ysp-desktop.exe` release 实测约 0.93 MB；
- 若复用本机 `F:\codex工作区\wallpaper-rs\bundled-mpv\mpv.exe`，完整目录约 122—130 MB；
- 若目标机器已有 mpv，可只分发小程序，通过 `YSP_MPV` 或 `PATH` 查找；
- WebView2 使用系统运行时，不把 Fixed Runtime 打进安装包；
- 不要把 mpv 的 DLL/字体/FFmpeg 资源重复打包。

```powershell
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
cargo build --release -p ysp-desktop
```

## 5. Android 构建命令

```powershell
$env:JAVA_HOME = 'D:\app\jdk-17.0.20.8-hotspot'
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'ysp-live\gradle-home'
cd android

# 单 ABI 调试包
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=arm64-v8a

# Kiosk/默认桌面
.\gradlew.bat :app:assembleKioskRelease -PyspAbi=arm64-v8a

# AAB
.\gradlew.bat :app:bundleStandardRelease -PyspAbi=arm64-v8a
```

输出位置：

```text
android/app/build/outputs/apk/standard/debug/app-standard-debug.apk
android/app/build/outputs/apk/kiosk/debug/app-kiosk-debug.apk
android/app/build/outputs/apk/standard/release/app-standard-release-unsigned.apk
android/app/build/outputs/apk/kiosk/release/app-kiosk-release-unsigned.apk
android/app/build/outputs/bundle/standardRelease/app-standard-release.aab
```

## 6. 体积优化

- 单 ABI 或 AAB，不发布 universal APK；
- 不删除 `libmpv.so` 依赖的 `libc++_shared.so` 或 FFmpeg 库；
- 保留 native 库的 16 KB 页对齐，不要用老 Gradle/NDK 重新压缩；
- 使用系统字体，不打包完整中文字体；
- 不把视频缓存、官方网页资源或广告素材塞进 APK；
- 开启 R8/资源压缩前先回归 JNI、WebView 注入和 WebView JS Interface；
- 体积优化不能牺牲 4K/8K 硬解、音画同步、首帧和遥控器响应。

## 7. 验收记录

- 记录每个 ABI 的 APK/AAB 实际字节数；
- 记录安装后 `pm path`/`du` 占用；
- 记录首次启动内存和连续播放 2 小时后的内存；
- 记录 libmpv native 崩溃、Surface 重建和 WebView 版本差异；
- 对 4K/8K/HDR/AVS3 逐路测试，不要用“能打开 m3u8”代替验收。
