# 合规与风险

## 1. 内容授权

本项目只演示技术可行性，不授予任何内容分发权。

- 央视、卫视直播内容有版权和转播权；
- “网页能播放”不等于“第三方 App 可以分发”；
- 商业产品必须取得央视/央视频和相应版权方的授权；
- 频道目录、台标、节目单、截图和节目名称都可能涉及商标/版权。

## 2. 服务条款

本项目不包含：

- DRM 解密；
- 绕过地区限制；
- 绕过付费/会员/广告；
- 伪造签名或冒用官方客户端；
- 批量抓取、转售或再分发内容。

如果官方页面/接口要求登录、地域或会员权限，应用应尊重官方结果，显示错误或引导用户使用官方客户端，不应尝试规避。

## 3. 第三方组件

| 组件 | 典型许可证 | 备注 |
|---|---|---|
| wry | MIT/Apache-2.0 | 桌面 WebView 封装 |
| tao | Apache-2.0/MIT | 窗口/事件循环 |
| mpv / libmpv | GPL-3.0-or-later（当前预编译 Android 包） | 与 FFmpeg 等二进制一起分发时，需履行 GPL 义务 |
| FFmpeg | GPL-3.0-or-later（当前 Android 预编译包） | 编解码器和编译选项决定义务 |
| `libmpv-kotlin-multiplatform` v0.3.0 | GPL-3.0-or-later | Android `jniLibs` 来源，详见 `THIRD_PARTY_NOTICES.md` |
| Media3/ExoPlayer | Apache-2.0 | 可选替代后端；当前工程未引入 |
| AndroidX WebKit | Apache-2.0 | DocumentStartJavaScript |
| Kotlin/Android | Apache-2.0 等 | 遵循 SDK 条款 |

发布前要：

- 固定依赖版本和 lockfile；
- 附带第三方许可证；
- 不使用来源不明的预编译 `.so`；
- 对外分发含当前 libmpv/FFmpeg 的 APK/AAB 时，提供匹配源码、GPL 文本和通知；
- 审查 H.264/AAC/HEVC/AVS3 专利许可；
- 明确 mpv/FFmpeg 是动态链接还是静态链接，以及对应义务。

## 4. 隐私

- WebView 会加载官方页面，可能产生官方 Cookie/本地存储；
- 应用不主动上传频道历史、播放 URL 或账号信息；
- 若增加远程日志/统计，必须脱敏并取得用户同意；
- 播放 URL 含短期签名，不能进入日志、崩溃上报或分析平台；
- 不持久化 token/query。

## 5. 安全

- WebView 限制导航白名单；
- 关闭文件访问、内容访问、多窗口和外部协议；
- JS Bridge 只接受结构化消息；
- 播放器 URL 二次校验；
- 不打开 `javascript:`、`file:`、`data:`；
- 不允许任意 URL 被注入到 Android WebView；
- 更新时必须重新验证官方页面和新版 WebView 行为。

## 6. 发布结论

没有内容授权，不应商业发行“全网央视/卫视聚合直播”。没有 mpv/FFmpeg 许可证审查，不应闭源分发自带 libmpv/FFmpeg 的安装包；当前 Android 原生库是 GPL-3.0-or-later 构建，完整清单见 `THIRD_PARTY_NOTICES.md`。技术 PoC 与商业产品之间还差授权、合规、设备验收和运维四道门槛。

