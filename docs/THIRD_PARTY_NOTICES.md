# 第三方许可证与分发边界

> 本文用于工程记录，不构成法律意见。发布 APK/AAB 前应按目标发行地区咨询熟悉 GPL、FFmpeg、专利和内容授权的律师。

## 1. 结论

本项目自身编写的 Rust/Kotlin 代码采用 MIT 许可，见仓库根目录 `LICENSE`。

但 Android APK/AAB 不是“整体 MIT”：

- Android 端播放依赖项目自带的 libmpv、FFmpeg 和其他原生库；
- 当前使用的预编译 Android 原生库整体按 **GPL-3.0-or-later** 分发；
- 因此，向第三方分发包含这些 `.so` 的 APK/AAB 时，不能只附上 MIT 许可证，也不能默认闭源分发；
- 内容版权、央视/央视频服务条款和 HLS/编解码器专利许可，是另外一套义务，不因软件许可证而消失。

个人本机构建、研究和未分发的内部验证，和对外商业分发不是同一个合规场景。正式发行前必须完成第 5 节的清单。

## 2. 项目自身代码

| 部分 | 许可证 | 说明 |
|---|---|---|
| Rust workspace | MIT | `Cargo.toml` 的 `license = "MIT"`，根目录 `LICENSE` |
| Kotlin/Android 宿主代码 | MIT | 由本项目编写，不包含上游 App 源码 |
| `assets/channels.json`、桥接脚本 | MIT | 不含抓包得到的 token、签名或完整播放 URL |

MIT 只覆盖本项目有权许可的代码，不覆盖预编译二进制、AndroidX/Kotlin 运行时和任何官方页面内容。

## 3. Android 预编译原生库

当前 `android/app/src/main/jniLibs` 中的原生库来自：

- 项目：`libmpv-kotlin-multiplatform`
- 版本：`v0.3.0`
- 发布页：<https://github.com/yuroyami/libmpv-kotlin-multiplatform/releases/tag/v0.3.0>
- 上游所述组件：mpv 0.41.0、FFmpeg 9.0.1
- Android 构建许可证：**GPL-3.0-or-later**

每个 ABI 目录包含：

```text
libmpv.so
libmpvkt_jni.so
libmpvkt_render.so
libavcodec.so
libavdevice.so
libavfilter.so
libavformat.so
libavutil.so
libswresample.so
libswscale.so
libc++_shared.so
libysp_core_android.so
```

`libysp_core_android.so` 是本项目 Rust 代码的 JNI 构建产物，但同一 APK 中的其他 GPL 原生库仍然适用 GPL 分发义务。

### 3.1 归档校验值

构建记录中使用的 Android 原生库归档 SHA-256：

| ABI | SHA-256 |
|---|---|
| `arm64-v8a` | `EB39891CF973FCED3D84A418FA6AC8C95104ADD122A9E7048BCDF4B27CF01B12` |
| `x86_64` | `0AE4DDC84DBE4E37992E27440B8411458FA4A986780BD93456FD96A6A2F26CE9` |

如果重新下载或升级原生库，必须重新记录校验值、版本、许可证和对应源码地址，不要用“来源不明的预编译 `.so`”替换。

## 4. 其他组件

| 组件 | 典型许可证 | 使用位置 |
|---|---|---|
| wry / tao | MIT/Apache-2.0 | Windows 隐藏 WebView 原型 |
| serde / serde_json | MIT/Apache-2.0 | Rust 核心数据解析 |
| thiserror | MIT/Apache-2.0 | Rust 错误类型 |
| url | MIT/Apache-2.0 | Rust URL 校验 |
| AndroidX Core / WebKit | Apache-2.0 | Android 宿主和 DocumentStartJavaScript |
| Kotlin / Android SDK | Apache-2.0 等，另受 SDK 条款约束 | Android 构建 |
| mpv / FFmpeg 传递依赖 | GPL/LGPL，按实际构建而定 | libmpv/FFmpeg 原生库 |

Rust 依赖的完整版本以 `Cargo.lock` 为准，Android 依赖以 Gradle 解析结果为准。不要只凭上表推断整个依赖树的许可证。

## 5. 分发 GPL 组件的最低检查清单

面向第三方分发 APK/AAB 时，至少应完成：

- [ ] 附带 `GPL-3.0-or-later` 完整许可证文本和上游版权/通知；
- [ ] 提供与所分发二进制精确对应的源码，或符合 GPL 要求的书面源码提供方式；
- [ ] 包含 FFmpeg/mpv/libass/libplacebo/dav1d 等依赖的构建配置、修改和脚本，足以复现二进制；
- [ ] 不删除或隐藏上游版权声明和许可证；
- [ ] 不把 GPL 组件与额外限制（例如禁止逆向、限制再分发）捆绑；
- [ ] 明确 APK 中的 MIT 代码与 GPL 二进制的边界，但不把“MIT 核心”当成整个包闭源的依据；
- [ ] 对静态/动态链接、打包方式、源码提供期限和安装包更新方式做法律审查；
- [ ] 单独确认 H.264/AAC/HEVC/AV1/AVS3 等编解码器专利许可；
- [ ] 单独取得央视/央视频及卫视内容的转播/分发授权，并遵守服务条款。

“源码在 GitHub 上公开”不等于自动满足所有 GPL 的对应源码和通知要求；至少要保证用户拿到的是与二进制匹配的源码与许可证。

## 6. 不含 GPL 原生库的备选发行方式

如果商业项目无法接受当前 GPL 分发边界，可以在不改 WebView 鉴权桥的前提下，用 Android 系统 `MediaCodec`/Media3 或其他许可证兼容的原生后端替换 `LibMpvPlayer`。这会改变播放器、硬解、包体和验收结果，必须重新测试，不能只替换依赖名称。

若选择闭源商业发行，必须重新做许可证设计，不应直接继续分发当前 `jniLibs` 目录。
