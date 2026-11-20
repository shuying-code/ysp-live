# Android libmpv 实现

## 1. 当前实现

Android 端不是“预留接口”，已经接入真实的 libmpv JNI：

- `LibMpvPlayer.kt`：创建 libmpv core、设置网络/缓存/硬解选项、加载 HLS、监听事件；
- `MpvNative.kt`：对预编译 `libmpvkt_jni.so` 的最小 JNI 方法声明；
- `PlaybackService.kt`：`mediaPlayback` 前台服务，持有 libmpv core 和通知；
- `BaseLiveActivity.kt`：手机用 `TextureView`、TV 用 `SurfaceView` 接收画面，把 `Surface` 交给 libmpv；
- `ChannelRepository.kt`：维护已验证的原生频道白名单；未列入的频道默认走官方兼容回退；
- `AuthWebViewBridge.kt`：隐藏 WebView 完成官方鉴权和 `get_live_info` 拦截；原生模式下暂停官方媒体和 WebView，官方兼容模式下铺满屏幕播放官方页面。

原生库位于：

```text
android/app/src/main/jniLibs/arm64-v8a/
android/app/src/main/jniLibs/x86_64/
```

每个 ABI 包含 `libmpv.so`、`libmpvkt_jni.so`、FFmpeg 的 `libav*.so`、`libsw*.so` 和 `libc++_shared.so`。

## 2. 上游版本和许可证

本项目使用：

- `libmpv-kotlin-multiplatform` v0.3.0 的 Android 预编译原生库；
- mpv 0.41.0；
- FFmpeg 9.0.1（Android 构建启用了 GPL）；
- libass、libplacebo、dav1d、Mbed TLS、HarfBuzz、FreeType 等依赖。

原始发布页：

```text
https://github.com/yuroyami/libmpv-kotlin-multiplatform/releases/tag/v0.3.0
```

该 Android 原生库整体按 **GPL-3.0-or-later** 分发。把 APK 交给第三方时，需要同时满足 GPL 的源代码、许可证和通知义务；不能把“Rust 工程源码 MIT”误认为 APK 可以闭源分发。完整说明见 `docs/THIRD_PARTY_NOTICES.md`。

## 3. 构建

### 3.1 Rust JNI（可选但推荐）

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_NDK_HOME = 'C:\Android\Sdk\ndk\26.1.10909125'
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
powershell -ExecutionPolicy Bypass -File .\scripts\build-android-rust.ps1 -Abi arm64-v8a
```

没有 `libysp_core_android.so` 时，Kotlin 仍有频道/URL 校验回退，不会阻止 APK 构建。

### 3.2 arm64 APK

```powershell
$env:JAVA_HOME = 'D:\app\jdk-17.0.20.8-hotspot'
$env:GRADLE_USER_HOME = Join-Path $env:LOCALAPPDATA 'ysp-live\gradle-home'
cd android
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=arm64-v8a --no-daemon
```

产物：

```text
android/app/build/outputs/apk/standard/debug/app-standard-debug.apk
```

Kiosk/默认桌面变体：

```powershell
.\gradlew.bat :app:assembleKioskDebug -PyspAbi=arm64-v8a --no-daemon
```

### 3.3 x86_64 模拟器

```powershell
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=x86_64 --no-daemon
```

仓库同时放入了 `x86_64` 原生库，但模拟器仍需要实际 Android 系统镜像和真机级 Surface/MediaCodec 行为验证。

## 4. 实测体积

2026-11-19 清理 Android 构建目录后重新构建的结果（不是估算；当前 APK 已包含 `libysp_core_android.so`）：

| 产物 | 字节数 | 约 |
|---|---:|---:|
| `standard` arm64 debug | 32,119,583 | 32.1 MB |
| `kiosk` arm64 debug | 32,120,410 | 32.1 MB |
| `standard` arm64 release unsigned | 31,587,300 | 31.6 MB |
| `kiosk` arm64 release unsigned | 31,587,972 | 31.6 MB |
| `standard` AAB arm64 | 15,203,349 | 15.2 MB |
| `standard` x86_64 debug | 38,184,752 | 38.2 MB |
| universal debug（arm64 + x86_64） | 68,120,196 | 68.1 MB |
| Rust JNI `libysp_core_android.so` arm64 | 574,208 | 0.57 MB |

每个 ABI 的 `jniLibs` 合计约 29.8 MB（arm64）和 35.9 MB（x86_64）；AAB 会由 Google Play 按设备 ABI 下发，下载体积明显小于 universal APK。当前 Gradle 的 strip 任务对预编译 `.so` 提示无法剥离，后续应使用匹配 NDK 的工具重新检查。

## 5. 真机验收

测试设备：

- Redmi K30S Ultra `M2007J3SC`；
- Android 12，arm64-v8a，1080x2400；
- `standardDebug`，包名 `com.ysp.live`。

已验收：

- CCTV1、CCTV5 官方播放器出画，音频轨启动；
- 北京卫视、江苏卫视原生路径可复现绿紫块；切到官方播放器后正常出画；
- 天津卫视 libmpv 原生播放：H.264、1920x1080、25 fps、`hwdec-current=mediacodec-copy`；
- 天津卫视稳定状态下只有一条原生 `AudioTrack`，隐藏 WebView 音轨在切台后约 100ms 内停止；
- 官方频道首次加载后通过页面内 `changeTV()` 快速切台，普通切台约 2.1–2.3 秒出画；
- 官方与原生之间切换、连续快切和重新鉴权均已验证；
- 播放界面不显示底栏或停止按钮；单击画面或按电视确认键唤起频道列表，5 秒无操作自动回到直播；系统媒体通知仍保留停止入口；
- 频道抽屉内置当天节目单，当前节目自动高亮；节目单请求与播放链路分离，不会中断正在播放的频道；
- 原生播放时按 Home 再回到应用，TextureView Surface 会重新绑定，画面和音频继续；
- 通知停止后不会自动重新鉴权，CCTV 和无媒体状态没有残留活跃音轨；
- 真机日志未发现 `FATAL EXCEPTION`。

当前策略不是“全部频道原生”，而是只把已验证频道放入 `NATIVE_VERIFIED_PIDS`。其他频道默认官方播放器，避免用户遇到已知异常码流。

已实现：

- 原生库打包、JNI 符号、ABI 依赖和 APK 构建；
- Surface 绑定、HLS `loadfile`、暂停/停止、前台服务通知；
- WebView 鉴权桥和 Rust/Kotlin URL 校验；
- 官方播放器兼容回退和单音轨抑制；
- 手机 `TextureView` 渲染与 TV `SurfaceView` 分路。

未验收：

- 未在 Android TV 上验证 Surface、D-pad、Kiosk 和遥控器媒体键；
- 未逐路验证其余 23 路卫视，也未验证 4K/8K/HDR/AVS3/VIP 频道；
- 未验证不同厂商 ROM 的后台保活、开机自启、WebView 版本和 D-pad；
- 未验证 GPL 发行合规、内容授权和长期接口稳定性。

## 6. 何时回退 Media3

如果目标机型出现 libmpv 无法解决的硬解/功耗/体积问题，可以保留当前桥协议，把 `LibMpvPlayer` 替换为 Media3/ExoPlayer 后端。当前工程为降低误报，已经移除 Media3 依赖；回退时需要重新加入 Media3，并重新做包体和硬解测试。不要把 Media3 说成“网页播放器”，它同样是原生播放。
