# 执行计划

## 1. 交付目标

交付一个 Android TV + Android 手机共用的直播应用，以及一个可在 Windows 上验证鉴权链路的 Rust 原型。最终播放必须由原生播放器完成，WebView 只作为官方鉴权桥。

当前版本定位为“可编译、可继续验收的工程基线”，不是已经完成商业验收的成品。

## 2. 版本与里程碑

| 阶段 | 目标 | 周期 | 当前状态 |
|---|---|---:|---|
| P0 | Rust 核心、频道目录、桥协议、单元测试 | 1—2 天 | 已落地，9 个测试通过 |
| P1 | Windows 隐藏 WebView + mpv 原型 | 1—2 天 | 已落地，已实测取流并启动 mpv |
| P2 | Android TV/手机双入口、libmpv、前台服务 | 3—5 天 | 已编译，待真机 Surface/硬解验证 |
| P3 | 开机自启、Kiosk/默认桌面、设备矩阵 | 3—5 天 | BootReceiver/Kiosk scaffold 已落地 |
| P4 | URL 刷新、错误恢复、EPG、频道刷新 | 1—2 周 | 部分接口已准备，功能待实现 |
| P5 | 8 小时 soak、4K/8K/ABI、打包、合规 | 2—4 周 | 未开始 |

## 3. 工作分解

### P0：共享核心

- 频道模型：名称、pid、streamId、kind、VIP/限免/4K。
- 64 路种子目录；校验空目录、重复 pid、非法 pid。
- 桥消息：
  - `live_info`；
  - `bridge_ready`；
  - `error`。
- PlayURL 校验：
  - 拒绝空值、超长、`javascript:`、`file:`、`data:`；
  - 拒绝非 HLS；
  - 拒绝带用户名/密码的 URL；
  - Debug 输出脱敏。
- URL 租约：默认 2 小时提示刷新，真正以播放器错误和官方页面重新鉴权为准。
- EPG URL：`https://capi.yangshipin.cn/api/yspepg/program/{pid}/{yyyyMMdd}`。
- 测试：真实样式的桥 JSON、危险 URL、重复 pid、目录计数、EPG 日期。

验收：`cargo test -p ysp-core` 全部通过。

### P1：Windows 原型

- `tao` + `wry` 创建窗口；
- 默认 2x2、不可见窗口，WebView2 加载官方 TV 页；
- 注入鉴权脚本，IPC 回调解析；
- 找 mpv：`YSP_MPV` → exe 同目录 → `PATH` → 常见安装位置；
- 启动 mpv：全屏、无边框、硬解、HLS 缓存、`Referer`；
- 支持 `--channel`、`--pid`、`--url`、`--list-channels`、`--run-seconds`、`--debug-visible`；
- 日志脱敏。

验收：

- `cargo check -p ysp-desktop` 通过；
- `--run-seconds 30` 能在日志看到 `bridge_ready` 或 `live_info`；
- 成功取到 HLS 后启动 mpv 并能播放至少 CCTV1、天津卫视。
- 若隐藏窗口导致 WebView 节流，保留 `--debug-visible` 作为兼容回退，并在文档中记录。

### P2：Android MVP

- 单 Application，双 Activity：
  - `PhoneMainActivity`：`LAUNCHER`；
  - `TvMainActivity`：`LEANBACK_LAUNCHER`。
- 1x1 隐藏 WebView，`addJavascriptInterface` + `DocumentStartJavaScript`。
- 频道列表：D-pad 可聚焦；显示央视/卫视和 VIP/限免标签。
- libmpv JNI HLS：
  - SurfaceView + `wid`/`vo=gpu`；
  - `auto-safe` 硬解，失败可回退软件解码；
  - Referer/User-Agent；
  - 错误监听触发重新鉴权；
  - 4K/8K 先不承诺硬解。
- 前台服务：
  - `foregroundServiceType="mediaPlayback"`；
  - Notification；
  - MediaSession 作为后续增强，当前未接入；
  - 从通知停止；
  - Activity 与 Service binder 复用播放器。
- Android JNI：Rust `ChannelCatalog`/`PlayUrl`；没有 `.so` 时 Kotlin 回退。

验收：

- `:app:assembleStandardDebug` 成功；
- 电视遥控器：进入频道列表、选台、返回、停止、重新鉴权均可操作；
- 手机：横屏/全屏、锁屏后播放不崩溃、通知可停止；
- 同一 APK 可以安装到手机和 TV（手机不要求 Leanback）。

### P3：自启和高优先级

- `RECEIVE_BOOT_COMPLETED` + `BootReceiver`；
- 先尝试启动入口 Activity；失败则启动 `mediaPlayback` 前台服务并显示通知；
- `kiosk` flavor 增加 `HOME` activity-alias；
- Device Owner 设备可增加：
  - 持久默认 HOME；
  - `setLockTaskPackages`；
  - `startLockTask()`。
- 不设置 `android:persistent="true"`，不伪造系统签名，不宣称普通 App 能获得系统级优先级。

验收：

- 普通手机：锁屏/重启/杀后台后行为有明确日志和通知回退；
- TV：标准版由 Leanback 启动；Kiosk 版设为默认 HOME 后可开机进入；
- Device Owner 测试设备可进入 Lock Task，退出需要管理员/DPM 授权。

### P4：稳定性与产品化

- 官方页面刷新频道/EPG；
- URL 过期、403、断网、CDN 切换；
- 首帧耗时、缓冲、切台、连续播放指标；
- 日志脱敏与远程诊断；
- 自动更新和版本回滚；
- 播放器后端抽象（libmpv 默认，Media3 仅作为可选回退）。

### P5：发布

- 按 ABI 拆分 AAB/APK；
- 目标机型硬解矩阵；
- 合规审查、隐私政策、内容授权；
- 发布渠道和回滚方案。

## 4. 验收门槛

### 功能

- 64 路频道可枚举，央视/卫视分组正确；
- 选取代表频道 10 路以上可获取 HLS；
- 至少 CCTV1、CCTV2、CCTV5、CCTV8K、天津卫视、北京卫视完成专项测试；
- 换台 100 次无崩溃、无永久黑屏；
- URL 过期/403 自动恢复，不要求用户重启 App；
- 断网 30 秒恢复后能重连或明确提示。

### 体验

- TV 遥控器焦点不丢失；
- 首帧目标 < 5 秒（50 Mbps Wi-Fi/有线）；
- 操作延迟 < 100 ms；
- 连续播放 8 小时内存无持续增长；
- 手机横屏/竖屏切换不崩溃。

### 合规

- 不使用 DRM 解密/绕过；
- 不绕过付费/地区/广告；
- 日志不包含 token/完整播放 URL；
- 发布前完成内容授权和 mpv/FFmpeg/Media3 许可检查。

## 5. 风险登记

| 风险 | 影响 | 应对 |
|---|---|---|
| 官方鉴权/页面变化 | 无法取流 | 隐藏 WebView 跟随官方页面；不硬编码签名 |
| Android WebView 版本旧 | 无 DocumentStartScript/MSE 异常 | 页面注入回退；目标机型实测；必要时预置策略 |
| 手机厂商限制自启 | BootReceiver 无效 | 通知回退；引导用户设置自启/电池白名单；不承诺静默 |
| TV 厂商后台限制 | 播放被暂停 | mediaPlayback 前台服务；Kiosk/Device Owner；OEM 验证 |
| 4K/8K/AVS3/HDR | 黑屏或软解卡顿 | 逐路探测；不支持则提示/回退官方 WebView |
| libmpv/FFmpeg 体积和许可 | arm64 debug 32.1 MB、release 31.6 MB、AAB 15.2 MB；GPL 分发义务 | 单 ABI/AAB；保留许可证和源码；必要时评估 Media3 回退 |
| 内容授权 | 不能商业发行 | 商业化前取得授权；不把网页可访问当成授权 |

