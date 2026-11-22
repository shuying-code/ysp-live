# 使用反馈指南

感谢使用 **YSP Live**。这是一个仍在快速验证阶段的直播客户端，设备、系统、WebView 和上游播放器差异都会影响结果。高质量反馈能显著缩短定位时间。

## 反馈入口

- **新建 Bug / 功能建议 / 使用咨询**：<https://github.com/shuying-code/ysp-live/issues/new/choose>
- **下载最新版本**：<https://github.com/shuying-code/ysp-live/releases/latest>
- **安全漏洞**：不要公开提交，请按 [SECURITY.md](../SECURITY.md) 私下上报。

## 提交前检查

- [ ] 已搜索现有 Issue，没有重复问题。
- [ ] 已阅读 README 的「已知限制」和对应平台文档。
- [ ] 使用的是最新 Release 或最新 `main` 构建。
- [ ] 已移除完整播放地址、token、Cookie、Apple ID、IP 和设备序列号等敏感信息。

## 必须提供的信息

| 项目 | 说明 | 示例 |
|---|---|---|
| 版本 / commit | Release 标签或提交号 | `v0.1.0 · 4a6542d` |
| 平台 | Android 手机 / Android TV / iPhone / Windows 原型 | `Android TV` |
| 设备与系统 | 型号、系统版本、CPU 架构 | `Redmi K30S Ultra · Android 12 · arm64` |
| 播放路径 | 原生播放器还是官方页内播放 | `libmpv` / `官方播放器` / `不确定` |
| 频道 | 频道名称和 PID（如能提供） | `天津卫视 · 600152137` |
| 网络 | Wi-Fi / 有线 / 移动网络，是否有代理 | `电信 Wi-Fi，无代理` |
| 复现步骤 | 从启动到出错的完整步骤 | 见下方示例 |
| 预期 / 实际 | 应该发生什么，实际发生什么 | 可附截图或录屏 |
| 日志 | 脱敏后的相关日志 | 保留错误码和时间点 |

## Bug 复现示例

```text
1. 安装 ysp-live-android-arm64.apk
2. 打开应用，等待天津卫视起播
3. 打开频道列表，切换到 CCTV1
4. 官方播放器开始加载，约 10 秒后停留在黑屏
5. 返回频道列表再切回天津卫视，仍黑屏
```

请同时说明问题是否必现、是否只在某个频道出现、切换网络后是否变化。

## 需要特别脱敏的内容

以下内容不要直接贴到 Issue：

- 完整的 `.m3u8` 地址及其 query；
- `cKey`、`token`、`ysign`、Cookie、Authorization；
- Apple ID、设备 UDID、签名证书信息；
- 内网地址、真实姓名、手机号等个人信息。

可以保留域名、错误码、HTTP 状态码、频道名和时间点。例如：

```text
host=live.example.ysp.cctv.cn
result=HTTP 403
channel=天津卫视
time=2026-11-22 20:15
```

## 平台专项信息

### Android 手机 / TV

- 是否安装/更新过 Android System WebView；
- TV 是否有遥控器，D-pad 哪个按键异常；
- 前台服务通知是否出现，应用退到后台后是否继续播放；
- 是否使用 `standard` 或 `kiosk` 变体。

### iPhone / iPad

- iOS 版本和安装方式（Sideloadly / AltStore / Xcode）；
- IPA 是否已签名，签名是否过期；
- 是否允许后台音频，是否开启低电量模式；
- 问题发生在首次鉴权、原生播放还是官方回退。

### Windows 原型

- mpv 版本和路径；
- WebView2 Runtime 版本；
- 是否使用 `--debug-visible` 观察官方页面；
- 日志位于 `%LOCALAPPDATA%\ysp-live\ysp-live.log`。

## Issue 标签与处理

| 标签 | 含义 |
|---|---|
| `bug` | 可复现的缺陷 |
| `enhancement` | 功能建议或改进 |
| `question` | 使用咨询 |
| `documentation` | 文档问题 |
| `need-repro` | 信息不足，等待补充复现步骤 |
| `upstream` | 官方页面、鉴权接口或上游码流问题 |

维护者会尽力处理，但项目没有商业 SLA，也无法承诺所有频道、设备和上游变化都能及时修复。

## 不接受的反馈方式

- 要求绕过 DRM、付费、会员、地区限制或内容授权；
- 公开贴出完整鉴权参数、播放令牌或他人隐私；
- 与项目无关的广告、引流或人身攻击；
- 重复提交同一个问题而不补充新信息。

反馈越具体，修复越快。谢谢支持。
