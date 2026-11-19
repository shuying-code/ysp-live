# 测试计划

## 1. 核心测试

```powershell
$env:CARGO_TARGET_DIR = Join-Path $env:LOCALAPPDATA 'ysp-live\cargo-target'
cargo test -p ysp-core
```

覆盖：

- 64 路频道目录、40 央视 + 24 卫视；
- 重复/非法 pid 拒绝；
- 真实样式桥 JSON 解析；
- `javascript:`、`file:`、非 HLS 拒绝；
- Debug 脱敏；
- URL 租约过期；
- EPG URL 和日期校验。

## 2. Windows 原型

### 编译

```powershell
cargo check -p ysp-desktop
cargo build --release -p ysp-desktop
```

### 取流冒烟

```powershell
$env:YSP_MPV = 'F:\codex工作区\wallpaper-rs\bundled-mpv\mpv.exe'
cargo run -p ysp-desktop -- --channel CCTV1 --run-seconds 30
```

检查 `%LOCALAPPDATA%\ysp-live\ysp-live.log`：

- 有 `WebView2 已创建`；
- 有 `bridge_ready` 或 `live_info`；
- 有 `已启动 mpv`；
- 没有完整 `playurl`、`ysign`、`token`。

如果隐藏 WebView 没有触发官方播放器：

```powershell
cargo run -p ysp-desktop -- --channel CCTV1 --debug-visible
```

观察页面是否加载、是否需要点击播放按钮；记录 WebView2 Runtime 版本。

### 码流抽样

- CCTV1；
- CCTV5；
- CCTV8K；
- 天津卫视；
- 北京卫视；
- 至少一个需要登录/VIP 的频道，确认错误路径和回退提示。

## 3. Android 静态构建

```powershell
$env:ANDROID_HOME = 'C:\Android\Sdk'
$env:ANDROID_NDK_HOME = 'C:\Android\Sdk\ndk\26.1.10909125'
$env:JAVA_HOME = 'D:\app\jdk-17.0.20.8-hotspot'
cd android
.\gradlew.bat :app:assembleStandardDebug -PyspAbi=arm64-v8a
.\gradlew.bat :app:assembleKioskRelease -PyspAbi=arm64-v8a
```

检查：

- 两个 APK 都能安装；
- TV APK 在 Leanback 启动器可见；
- 手机 APK 在桌面可见；
- Kiosk 版在系统 HOME 选择器可见；
- 当前 APK 应包含 `lib/arm64-v8a/libysp_core_android.so`、`libmpv.so` 和 `libmpvkt_jni.so`；
- `NativeCore.isNativeLoaded` 为 true；
- 如果故意移除 Rust JNI，Kotlin 校验回退不应阻止 UI 启动，但 libmpv 播放仍依赖原生库。

## 4. Android 真机

### 基础

- 冷启动 20 次无崩溃；
- 切换频道 100 次；
- 返回键/遥控返回键行为正确；
- 通知停止、重新鉴权、设置保存；
- 横竖屏/待机/唤醒；
- 锁屏播放（手机）和 HDMI 切换（TV）。

### 网络

- Wi-Fi 50 Mbps 首帧 < 5 s；
- 4G/5G 默认清晰度降低；
- 断网 30 s 恢复；
- CDN 403/404；
- URL 过期后重新鉴权；
- DNS 污染/代理/企业证书不绕过。

### 生命周期

- Activity 重建后复用播放器；
- 后台 30 分钟后返回；
- 系统回收 WebView 渲染进程；
- 系统回收播放服务；
- 低内存设备；
- 播放 8 小时；
- 温度/功耗；
- 4K/8K/AVC/HEVC/AVS3/HDR 逐路记录。

## 5. 开机自启专项

按 `ANDROID_AUTOSTART_AND_PRIORITY.md` 的设备矩阵记录：

- 冷启动是否进入 Activity；
- 如果失败，前台服务是否启动；
- 如果服务也失败，通知是否出现；
- 用户点击通知能否进入；
- 锁屏/待机后是否继续；
- 被“强行停止”后是否还能收到 BOOT_COMPLETED；
- Kiosk 版设为默认 HOME 后是否开机直达；
- Device Owner + Lock Task 的退出路径。

不把“某一次真机成功”写成“所有 Android 都能自启”。

## 6. 发布前

- `cargo fmt --check`；
- `cargo clippy`（可选，先处理项目自身问题）；
- Android lint；
- APK/AAB 签名；
- 隐私政策；
- 内容授权；
- 许可证和第三方通知；
- 崩溃上报脱敏；
- 灰度/回滚。

