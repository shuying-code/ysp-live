# Rust 电视端播放软件技术方案

> 目标：在电视/电视盒子上播放央视频 TV 页中的央视和卫视直播。
> 结论日期：2026-11-19。接口与页面会变化，以下内容应作为可行性依据，不应把 URL 或签名写死。

> **执行版说明（2026-11-19）**：本文保留早期方案对比和 Linux/Windows/Tizen 的可行性分析。项目的实际执行版本已经确定为 **Android TV + Android 手机：Kotlin 宿主 + Rust 共享核心/JNI + 隐藏 WebView 鉴权桥 + libmpv JNI 原生播放**；真机验证发现 CCTV 和部分卫视上游 H.264 异常时使用官方 WebView 兼容回退。当前包体和已实现状态以 `README.md`、`ARCHITECTURE.md`、`LIBMPV_ANDROID.md` 和 `PACKAGING_AND_SIZES.md` 为准；下文出现 Media3/ExoPlayer 的地方，除明确标注为可选回退外，均不是当前默认播放器。

## 1. 结论

**不是必须把整个软件做成内嵌浏览器。**

- **播放层不需要浏览器**：央视频 TV 页最终使用的是标准 HLS（`.m3u8` + MPEG-TS 分片），Rust 可以通过 libmpv、FFmpeg 或 GStreamer 原生播放。
- **取流层不建议纯原生复刻**：官方播放器使用动态 token、`cKey`、`signature` 等参数，接口不是公开稳定 API。最稳妥的工程方案是：
  - 用一个**隐藏/离屏的 WebView**加载官方页面，让官方 JS 自己完成鉴权和 `get_live_info`；
  - 拦截 `get_live_info` 返回的 `playurl`；
  - 把 `playurl` 交给 Rust 侧的 libmpv 原生播放。

这就是“**隐藏 WebView 取流 + 原生播放器**”的混合方案。它仍然用了 WebView，但 WebView 只是鉴权桥，不是播放器和 TV UI。

只有拿到央视频/央视的**正式 TV 端 API 或内容合作授权**时，才建议做完全无浏览器的纯原生实现。

## 2. 实测依据

对 `https://yangshipin.cn/tv/home?pid=600152137` 做了真实浏览器加载和网络抓包，观察到：

1. 页面频道数据实际包含两组：
   - 央视约 40 路：CCTV1—CCTV17、CGTN、CCTV4K、CCTV8K、剧场/付费频道等；
   - 卫视约 24 路：北京、江苏、东方、浙江、湖南、天津等。
   - 页面默认 UI 可能只显示卫视，不能按当前屏幕文本判断频道数量；央视数据在同一个 `tvChannel` 模块里。
2. 频道数据结构关键字段：
   - `pid`：频道页面 ID；
   - `streamId`：播放内容 ID，播放器请求里对应 `cnlid`；
   - `channelType`：`yangshi` / `weishi`；
   - `payType`、`viewRights`：付费/权限信息，必须尊重。
3. 播放器请求链路：
   - `POST https://player-api.yangshipin.cn/v1/player/auth`
   - `POST https://player-api.yangshipin.cn/v1/player/get_live_info`
   - `get_live_info` 返回 `data.playurl`，即 HLS 地址。
4. 浏览器 `<video>` 的 `currentSrc` 是 `blob:`，这只说明使用了 MSE，**不是 DRM 的证据**。网络层真实请求是 `.m3u8` 和 `.ts`。
5. 抽查 CCTV1、天津卫视、CCTV8K 的 `_web` 流：
   - M3U8 中没有 `EXT-X-KEY`；
   - TS 解析结果为 H.264 视频 + AAC 音频；
   - CDN 响应带 `Access-Control-Allow-Origin: *`；
   - 说明这些 `_web` 流可以直接交给 libmpv/FFmpeg 播放。
6. `playurl` 带 `svrtime`、`ysign` 等参数，会过期；`get_live_info` 还需要播放器 JS/WASM 生成的 `cKey`、`signature`。

因此：

- **原生解码完全可行**；
- **难点是官方取流鉴权，不是 HLS 解码**；
- 不要把某一次抓到的 `.m3u8` 写进代码，也不要在客户端伪造签名。

## 3. 方案对比

| 方案 | 是否用 WebView | 优点 | 缺点 | 适用 |
|---|---|---|---|---|
| 纯 WebView / Tauri | 是 | 最快出原型，官方播放器兼容性最好 | TV 遥控器体验差、内存高、网页广告/会员 UI 不可控、HLS 硬解不可控 | 个人验证、临时方案 |
| 隐藏 WebView 取流 + Rust 原生播放 | 是（仅鉴权） | 原生 TV UI、硬解、遥控器体验好；官方改签名时仍能工作 | 需要处理 WebView 生命周期、token 刷新、取流失败回退 | **推荐** |
| 纯原生 API + libmpv | 否 | 架构最干净、无浏览器 | 私有签名/加密易变，逆向成本高，合规风险高 | 有官方合作 API 时 |
| Android TV 原生 Kotlin + Media3 | 否 | 硬解/DRM/电视生态最好 | UI 不是 Rust；需要 Kotlin/Java 层 | Android TV 商业产品 |
| 三星 Tizen / LG webOS 原生 Rust | 不适用 | — | 平台不提供通用 Rust 原生应用运行时 | 只能做 Web 应用或厂商合作 |

## 3.1 打包体积对比

以下是**量级估算**，不是精确值；取决于是否携带运行时、是否启用硬解、FFmpeg 编解码器数量、是否带中文字体以及安装包压缩方式。

| 方案 | 安装包（压缩后） | 安装后占用 | 主要体积来源 |
|---|---:|---:|---|
| 纯 WebView / Tauri，使用系统 WebView2/WebKitGTK | Windows 3—10 MB；Linux 8—20 MB；Android 8—20 MB | Windows 10—30 MB；Linux 20—60 MB；Android 20—50 MB | Rust 程序 + 前端资源；WebView 由系统提供 |
| 纯 WebView / Tauri，自带 WebView 运行时 | Windows 130—200 MB；Linux 200—350 MB | Windows 180—280 MB；Linux 300—500 MB | WebView2/WebKitGTK/WPE 运行时 |
| **隐藏 WebView + libmpv（当前执行版）** | Windows 35—70 MB；Linux 40—90 MB；Android arm64 debug 32.1 MB / release 31.6 MB | Windows 80—160 MB；Linux 100—220 MB；Android 约 32 MB 安装包 | libmpv/FFmpeg 约 29.8 MB/arm64 + 程序 + 资源 |
| 纯原生 API + libmpv | Windows 30—65 MB；Linux 30—80 MB | Windows 70—150 MB；Linux 80—200 MB | libmpv/FFmpeg；无浏览器运行时 |
| Android TV：Kotlin/Compose + Media3 | 8—20 MB APK | 20—50 MB | Compose + Media3；WebView 由系统提供 |
| Android TV：Media3 + libmpv 原生库 | 40—90 MB APK（按 ABI 拆分） | 80—180 MB | 多 ABI 的 libmpv/FFmpeg `.so` |
| 三星 Tizen / LG webOS Web 应用 | 1—5 MB | 5—15 MB | HTML/JS/图片资源 |

几个容易误判的点：

- **Rust release 二进制本身通常只有 4—15 MB**（stripped）。体积大头是 WebView 和视频运行时。
- **Windows WebView2**：多数 Windows 10/11 已预装 Evergreen Runtime；不带运行时安装包可以只有几 MB。需要离线安装时，Fixed Version Runtime 通常增加 130—200 MB。
- **Linux WebKitGTK/WPE**：如果 TV 系统镜像已经带 WebKit，应用可以很小；如果要 AppImage/Flatpak 自带，体积会迅速到 200 MB 以上。
- **libmpv/FFmpeg**：只保留 H.264/AAC + HLS 的精简构建约 20—40 MB；完整编解码器构建约 40—100 MB。4K/8K/AVS3 会进一步增加。
- **Android 多 ABI**：libmpv/FFmpeg 按 `arm64-v8a`、`armeabi-v7a`、`x86_64` 分别打包；AAB 按设备下发可显著减小下载体积，universal APK 会明显变大。
- **中文字体**：如果打包完整 Noto Sans SC，可能增加 10—20 MB；TV 有系统中文字体时优先使用系统字体，或只做子集化。
- **Electron/CEF 对照**：Electron 通常 80—150 MB 安装包、200—300 MB 安装后；CEF 通常 100—200 MB 安装包、250—400 MB 安装后。Rust + WebView 的主要优势之一就是更小。

体积控制建议：

- 优先使用系统 WebView；只有离线安装/兼容性要求高时才自带。
- 用 `strip`、`lto = "thin"`、`codegen-units = 1`、`opt-level = "s"` 构建 release。
- Linux 用发行版/固件提供的 libmpv、GStreamer、WebKitGTK，避免重复打包。
- Android 用 AAB + ABI split，不要打 universal APK。
- 只为目标设备打包需要的编解码器，不要默认全量 FFmpeg。

## 4. 推荐架构

```text
┌────────────────────────── Rust TV App ──────────────────────────┐
│                                                                  │
│  Slint / GTK4 / egui TV UI                                      │
│        │                                                         │
│        ├── 频道列表、EPG、收藏、设置、遥控器焦点                  │
│        │                                                         │
│        ▼                                                         │
│  ChannelService ───────────────► SQLite（频道/收藏/上次播放）     │
│        │                                                         │
│        ├── 节目单：reqwest → capi.yangshipin.cn/api/yspepg/...   │
│        │                                                         │
│        ▼                                                         │
│  AuthBridge（隐藏 WebView）                                      │
│        │  加载官方 TV 页，注入 XHR/fetch 拦截器                   │
│        │  捕获 get_live_info.data.playurl                        │
│        ▼                                                         │
│  PlayerService（libmpv / mpv IPC）──► 显示 HLS 视频              │
│        │                                                         │
│        └── 失败/不支持的频道 → 回退到可见官方 WebView             │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 组件职责

- **UI**：只做 TV 原生界面，不承载视频解码。推荐 Slint（TV 焦点和主题容易做）或 GTK4（Linux 生态成熟）。
- **AuthBridge**：隐藏 WebView，运行官方页面。只关心 `get_live_info` 的 JSON，不解析视频。
- **PlayerService**：libmpv 或独立 mpv 进程。负责 HLS 拉流、硬解、音轨、重连、缓冲。
- **EPG**：直接用 `reqwest` 请求节目单接口，不需要 WebView。
- **Fallback**：当取流失败、签名变化、码流不支持或出现 DRM 时，显示官方 WebView 全屏播放，保证“至少能看”。

## 5. 关键实现

### 5.1 WebView 侧：拦截 `get_live_info`

必须用“初始化脚本”在页面 JS 之前注入。核心是拦截 XHR；若播放器改用 `fetch`，再补一个 `fetch` 包装。

```js
const postLiveInfo = (data) => {
  if (!data?.data?.playurl) return;
  window.ipc?.postMessage(JSON.stringify({
    type: 'live_info',
    playurl: data.data.playurl,
    defn: data.data.defn,
    formats: data.data.formats || []
  }));
};

const origOpen = XMLHttpRequest.prototype.open;
XMLHttpRequest.prototype.open = function (method, url, ...rest) {
  this.__yspUrl = String(url);
  return origOpen.call(this, method, url, ...rest);
};

const origSend = XMLHttpRequest.prototype.send;
XMLHttpRequest.prototype.send = function (...args) {
  this.addEventListener('load', () => {
    if (this.__yspUrl?.includes('/v1/player/get_live_info')) {
      try { postLiveInfo(JSON.parse(this.responseText)); } catch (_) {}
    }
  });
  return origSend.apply(this, args);
};
```

注意：

- 上面使用 wry 的 `window.ipc.postMessage`；如果直接使用 WebView2，则换成 `window.chrome.webview.postMessage`；WebKitGTK/WPE 则使用对应的 `webkit.messageHandlers`。
- 不要用 `video.currentSrc`，它通常是 `blob:`，不能交给 mpv；
- 只捕获 `playurl` 和必要元数据，不记录 token；
- 切换频道时重新导航到 `?pid=...`，让官方播放器重新请求；
- WebView 建议小尺寸、静音、置后/离屏，但不要完全 `display:none`，避免页面节流导致播放器不启动；实际目标设备要验证。

### 5.2 Rust 侧：把 URL 交给 mpv

原型阶段最简单的是独立 mpv 进程 + IPC；产品阶段再考虑 libmpv render API 嵌入 UI。
Slint/egui 本身不提供现成的视频控件，libmpv 要么通过 render API 渲染到 OpenGL/Vulkan 纹理再与 UI 合成，要么先用独立 mpv 窗口做原型；后者不适合作为最终 TV UI。

```text
mpv --idle=yes --force-window=yes --fullscreen=yes --no-osc --no-border
    --hwdec=auto-safe --vo=gpu-next --cache=yes
    --demuxer-max-bytes=128MiB --network-timeout=15
    --http-header-fields="Referer: https://yangshipin.cn/"
    --input-ipc-server=<平台对应的 IPC 地址>
```

播放/切换：

```json
{"command":["loadfile","<get_live_info 返回的 playurl>","replace"]}
{"command":["set_property","pause",false]}
```

生产环境要做：

- `playurl` 过期或被 CDN 拒绝时，重新触发官方播放器取流；
- 连续 8 秒无 `time-pos` 前进，判定卡死并重载；
- 记录首帧耗时、卡顿次数、当前清晰度，不记录 token；
- 对 4K/8K/HDR/AVS3 等频道逐路验证，不支持就隐藏或回退 WebView。

### 5.3 EPG

节目单可以原生请求：

```text
GET https://capi.yangshipin.cn/api/yspepg/program/{pid}/{yyyyMMdd}
```

用 `reqwest` + `serde_json` 解析即可。频道列表建议从官方 TV 页数据生成一份种子 JSON，并在启动时刷新；不要长期写死 `pid`/`streamId`。

## 6. 按目标设备选型

### Linux 电视盒 / 嵌入式盒子（最适合 Rust）

- UI：Slint 或 GTK4；
- 视频：libmpv（或 GStreamer）；
- 鉴权桥：WebKitGTK/WPE；
- 硬解：VAAPI / V4L2 / 厂商 SDK；
- 适合 Rockchip/Amlogic 等定制 Linux 盒子，但需要处理 Yocto/Buildroot 打包。

### Windows 迷你主机 / HTPC

- UI：Slint/egui；
- 视频：libmpv；
- 鉴权桥：WebView2（wry/Tauri）；
- 开发最快，适合先做原型；不是严格意义上的“电视原生应用”。

### Android TV

- **不建议为了 Rust 而强行用 Rust 写整个 TV/手机 UI**；
- 当前执行版采用 Kotlin/Android View 宿主 + Rust 共享核心/JNI；
- 视频由 libmpv JNI + `SurfaceView` 原生播放，WebView 只做官方鉴权桥；
- Media3/ExoPlayer 保留为许可证、硬解或功耗不满足时的可选替代后端，不是当前依赖；
- 商业产品优先争取官方 API，避免长期依赖网页签名。

### 三星 Tizen / LG webOS / 品牌自研系统

- 通常不能直接安装 Rust 原生二进制；
- 做 Web 应用（HTML/JS）或与厂商合作；
- “Rust 原生播放器”在这类电视上不是可行路线。

## 6.1 电视 + 手机双端方案

如果目标是 **Android TV + Android 手机**，推荐一套 Android 工程、两个入口，而不是两套完全独立的 App：

```text
┌─────────────────── Rust Core（UniFFI/JNI）───────────────────┐
│ 频道模型 / EPG / 缓存 / 重试 / 取流协议 / 日志                 │
└──────────────────────────┬────────────────────────────────────┘
                           │
          ┌────────────────┴────────────────┐
          ▼                                 ▼
  Android TV 入口                    Android 手机入口
  Compose for TV                     Compose
  libmpv JNI + SurfaceView            libmpv JNI + SurfaceView
  隐藏 WebView 鉴权桥                隐藏 WebView 鉴权桥
  遥控器焦点/频道数字键              触摸/横屏全屏/手势
```

### 为什么不让 Rust 同时渲染 TV 和手机 UI

- TV 需要 D-pad 焦点、频道数字键、10-foot UI；手机需要触摸、横竖屏、手势和生命周期管理。
- Android TV/手机的系统集成、后台和生命周期必须由 Kotlin 宿主处理；当前视频播放由 libmpv 负责，Media3 仅作为可选后端。libmpv 会增加包体和功耗，必须按机型验收。
- Rust 适合做共享核心，不适合强行统一两端的视频表面和 UI 框架。

### 代码组织建议

```text
android/
  app/                 # 单 Application，两个 Activity/入口
    PhoneMainActivity
    TvMainActivity
  core-ui/             # 两端共享的频道、EPG、设置组件
  player/              # libmpv 封装、HLS、重连、错误回退
  authbridge/          # 隐藏 WebView + 取流协议
rust-core/             # cdylib/staticlib，UniFFI 生成 Kotlin 绑定
```

Android TV 的 Manifest 需要：

```xml
<uses-feature android:name="android.software.leanback" android:required="true" />
<uses-feature android:name="android.hardware.touchscreen" android:required="false" />
```

### 手机端注意事项

- 手机端官方页会走移动版（`m.yangshipin.cn`），TV 端走桌面/TV 页；两个入口分别做鉴权桥，但都捕获同一个 `get_live_info`。
- 横屏全屏播放；支持刘海/挖孔安全区；进入后台时按策略暂停或释放 libmpv/Surface。
- 移动网络下降低默认清晰度，Wi-Fi 下允许 1080P；不要默认 4K/8K。
- 可选：手机投屏到 TV（Chromecast/AirPlay），但不要把投屏当成 TV 端主方案。
- 手机和 TV 可以共享同一套频道/EPG 缓存格式；账号同步需要后端，不是播放链路的必需项。

### 如果还要 iPhone

- Rust 核心通过 UniFFI 生成 Swift 绑定；
- iOS UI 用 SwiftUI；
- 鉴权桥用隐藏 `WKWebView`；
- 播放用系统 `AVPlayer`（原生支持 HLS）；
- 不要强行在 iOS 上使用 libmpv。

### 双端打包体积

| 形态 | 下载/安装包 | 安装后占用 | 备注 |
|---|---:|---:|---|
| Android 单 AAB（手机 + TV，libmpv 当前实测） | 15.2 MB | 约 30—60 MB（待真机 `du`） | 当前 arm64 AAB；Rust 核心约 0.57 MB |
| Android APK（单 ABI） | 8—20 MB | 20—50 MB | 适合侧载/电视盒子 |
| Android + libmpv 原生库（当前实测） | 32.1 MB arm64 debug；31.6 MB arm64 release；68.1 MB universal debug | 安装后按机型实测 | 单 ABI/AAB 可控制体积；GPL 义务必须处理 |
| iOS App | 15—35 MB | 40—80 MB | SwiftUI + AVPlayer + Rust 核心 |
| Tauri/WebView 双端 | 8—20 MB/端 | 20—50 MB/端 | 体积小，但视频和 TV 遥控体验较差 |

## 7. 迭代计划

### P0：可行性验证（1—2 天）

- 用 wry/WebView2 加载 TV 页；
- 注入拦截器，打印 `get_live_info` 的 `playurl`；
- 用 mpv 播放 CCTV1、天津卫视等 10 路；
- 验证 1080p 首帧、切台、连续播放 2 小时；
- 验证 `playurl` 过期后的表现。

### P1：可用 MVP（1—2 周）

- 原生频道列表 + 收藏 + 上次播放；
- 遥控器按键映射（方向/OK/返回/频道加减/数字键）；
- EPG 节目单；
- token 过期刷新、mpv 崩溃重启、网络断开提示；
- 官方 WebView 回退入口。

### P2：产品化（4—8 周）

- 硬解和 4K/8K 兼容性测试；
- 8 小时 soak test、内存/温度/网络抖动测试；
- 安装包、自动更新、日志和远程诊断；
- 安全加固：WebView 导航白名单、禁止 devtools、token 不落盘。

### P3：合规化

- 与央视/央视频确认 TV 端内容授权和 API 合作；
- 有正式 API 后，用纯原生替换 WebView 鉴权桥；
- 商业闭源发行前审查 mpv/FFmpeg 的 GPL/LGPL 配置、H.264/AAC 专利和内容版权。

## 8. 验收标准

- 64 路频道可枚举，央视/卫视分组正确；
- 有线/50 Mbps Wi-Fi 下 1080p 首帧 < 5 秒；
- 连续切台 100 次无崩溃、无黑屏累积；
- 连续播放 8 小时，内存无持续增长；
- token 过期后自动恢复，不需要用户重启；
- 4K/8K/不支持码流有明确提示或回退；
- 断网 30 秒恢复后自动重连；
- 遥控器操作延迟 < 100 ms，焦点不丢失。

## 9. 风险与合规

1. **内容授权**：央视/卫视直播内容有版权，网页可看不等于允许第三方 App 分发。商业产品必须先取得授权。
2. **服务条款**：抓取播放地址、绕过地区/付费/广告限制可能违反服务条款；本方案不包含任何破解 DRM、去广告或绕过付费的逻辑。
3. **私有接口变化**：`cKey`/`signature`/页面结构随时可能变；混合方案把变化隔离在官方 WebView 内，比纯原生稳。
4. **DRM/编码**：当前抽查是明文 H.264/AAC，但不能保证所有频道/清晰度永远如此；遇到 DRM 应回退官方播放器，不要尝试解密。
5. **WebView 可用性**：Android TV 系统 WebView 版本、Linux WebKitGTK/WPE 的 MSE/硬解能力差异很大，必须按目标机型实测。
6. **许可证**：当前 Android libmpv/FFmpeg 预编译库是 GPL-3.0-or-later，闭源商业分发有明确义务；如不能接受，应重新设计播放器后端并做法律审查。

## 10. 最终建议

- **个人验证/最快出原型**：Tauri 或 wry 包官方 TV 页，1 天可跑；但不要把它当成最终 TV 产品。
- **当前执行形态**：Kotlin Android TV/手机宿主 + Rust 共享核心 + libmpv + 隐藏 WebView 鉴权桥；官方 WebView 仅作为取流失败回退候选。
- **许可证优先的替代方案**：在不改变鉴权桥协议的前提下，将 `LibMpvPlayer` 替换为 Media3/系统 MediaCodec，并重新做硬解、包体和设备验收。
- **没有官方授权**：不要做“全网央视/卫视聚合”商业软件；先解决内容授权，再谈技术。
