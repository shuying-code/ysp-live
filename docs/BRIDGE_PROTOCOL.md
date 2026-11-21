# 鉴权桥协议

## 1. 原则

WebView 只负责让官方页面/播放器完成自己的鉴权流程。宿主只接收官方播放器已经生成的 HLS 地址，不解析或伪造 `cKey`、`signature`、`token`。

## 2. 消息方向

### 页面 → 宿主

#### `bridge_ready`

```json
{
  "type": "bridge_ready",
  "url": "https://yangshipin.cn/tv/home?pid=600152137"
}
```

用途：确认注入脚本已运行、页面已进入可观察状态。宿主不能把 `bridge_ready` 当作取流成功。

#### `live_info`

```json
{
  "type": "live_info",
  "playurl": "https://cdn.example.invalid/opaque-path/channel.m3u8?from=player&...",
  "defn": "fhd",
  "livepid": "600152137",
  "formats": [
    {
      "id": 2019927003,
      "fn": "fhd",
      "fnname": "蓝光 1080P",
      "defnrate": "1080P"
    }
  ],
  "backupurls": [
    "https://backup.example.invalid/opaque-path/channel.m3u8?from=player&..."
  ]
}
```

约束：

- `playurl` 必须是 HTTPS、主机非空、路径以 `.m3u8` 结尾；
- 不允许 `javascript:`、`file:`、`data:`、`blob:`；
- 不把 `vkey`、`ysign`、`token`、完整 query 或完整路径写入日志；
- `formats` 只保留公开清晰度元数据；
- `backupurls` 来自同一份官方 `get_live_info` 响应的 `backurl_list`，只接受 HTTPS、路径以 `.m3u8` 结尾、与主地址不同的条目，最多保留 4 条；
- 宿主持久化或上报时只允许记录备用线路数量和主机名，不能记录完整 URL；
- 同一个 URL + 清晰度组合在同一页面生命周期内只上报一次；宿主主动请求新清晰度前会清除该去重状态。

#### `playing`

```json
{
  "type": "playing",
  "width": 1920,
  "height": 1080,
  "backup": false
}
```

`width/height` 取自当前视频元素的真实视频尺寸；宿主只有在尺寸大于零时才撤销启动加载层，避免把
`playing` 事件误当成已经出画。`backup` 为 `true` 表示当前出声和出画的是宿主创建的备线
`<video>`，不是页面原播放器。宿主可以据此显示“正在播放 · 备用线路”，且不能再把原播放器的
播放事件当成主线路恢复。

#### `buffering`

```json
{
  "type": "buffering",
  "width": 1920,
  "height": 1080,
  "backup": true
}
```

#### `playback_error`

```json
{
  "type": "playback_error",
  "backup": false,
  "message": "video error 2"
}
```

用途：官方页内 `<video>` 或宿主备线播放失败时通知宿主继续尝试下一条官方备线、降档或重新鉴权。
备线激活后，原页面视频的尺寸、起播、缓冲和错误事件都会被忽略，避免旧播放器冒充备线出帧或重复
故障计数。备线沿用同一视频元素内的音轨，暂停并静音页面原播放器，以保持单音轨并由系统统一同步。

#### `error`

```json
{
  "type": "error",
  "code": "page_error",
  "message": "简短错误描述"
}
```

页面错误只用于提示，不应包含鉴权 header 或完整 URL。

### 宿主 → 页面

宿主不发送鉴权指令。需要切换清晰度时调用：

```javascript
window.__yspApplyLiveQuality("fhd");
```

参数为 `formats[].fn`。页面会调用官方播放器的 `getLiveUrlsByVid(pid, vid, fn, 0)`，由官方页面重新生成该档鉴权流。宿主按 `defn` 过滤响应，并在超时或播放失败后请求下一档。

主线路持续失败或卡顿时，宿主可以要求页面接管备线：

```javascript
window.__yspPlayBackupUrl("https://backup.example.invalid/.../channel.m3u8?...");
window.__yspStopBackupPlayer();
```

`__yspPlayBackupUrl` 会静音并暂停页面原 `<video>/<audio>`，创建独立的 `#yspBackupVideo`，使用页面已加载的 HLS.js 播放官方备用 CDN。备线激活期间 `__yspGetOfficialState()` 返回备线视频状态，便于宿主的卡顿看门狗继续工作。

需要重新鉴权时，由宿主重新导航到：

```text
https://yangshipin.cn/tv/home?pid=<pid>
```

或调用 WebView reload，让官方播放器重新发起自己的请求。

## 3. 拦截点

`assets/auth_bridge.js` 同时包装：

- `XMLHttpRequest.prototype.open/send`；
- `window.fetch`。

目标 URL：

```text
/v1/player/get_live_info
```

匹配使用 `URL.pathname`，不要只做字符串 includes，避免误拦截其他路径。兼容旧实现时可以使用路径包含作为兜底。

## 4. 启动时序要求

初始化脚本必须在官方播放器脚本之前执行：

- Windows：`wry::WebViewBuilder::with_initialization_script`；
- Android：`WebViewCompat.addDocumentStartJavaScript`；
- 不支持 DocumentStartScript 的旧 WebView：`onPageStarted` 回退注入，并记录兼容性告警。

如果注入太晚，官方播放器可能已经完成 `get_live_info`，本次取流只能靠重新加载页面恢复。

## 5. Host 侧校验

Windows `ysp-core` 和 Android `AuthWebViewBridge` 都会再次校验：

- 消息是合法 JSON；
- `type` 是已知类型；
- `playurl` 非空且长度受限；
- 协议是 HTTPS（Android 强制，桌面 core 允许 HTTP 但默认页面是 HTTPS）；
- 主机存在；
- 路径以 `.m3u8` 结尾；
- 不含用户名/密码；
- 页面 host 在 `yangshipin.cn` 白名单内。

## 6. 双播放器问题

官方 WebView 播放器在浏览器里使用 MSE，`video.currentSrc` 通常是 `blob:`，不可交给 mpv/libmpv。

成功捕获 `playurl` 后：

1. 原生播放器开始拉流；
2. JS 把官方 `<video>` 静音并暂停；
3. WebView 页面继续存在，用于后续鉴权；
4. 原生播放失败或 URL 过期时，重新加载页面重新取流。

官方兼容播放器切到备线时：

1. 原页面播放器及所有 `<audio>` 暂停、静音；
2. 只保留一个非静音备线 `<video>`；
3. `playing/buffering` 消息携带 `backup: true`；
4. 切台、降清晰度、恢复原播放器或重新鉴权前销毁备线播放器；
5. 销毁备线时恢复 `#app` 的显示并触发一次 `resize`，避免官方页停在故障态时保留 `display: none`，导致切台后只有黑屏。

央视网页 `tv.cctv.com/.../m/` 兜底在当前 Android WebView 和 iOS WKWebView 真机上都没有可用的 Widevine 解码路径，默认关闭，不再把网页中的“无可用播放器”当作可用备线。可用优先级是：官方 `backurl_list` 备线 → 官方低清晰度 → 重新鉴权。

## 7. 刷新策略

- `ysp-core` 的默认租约是 2 小时，仅用于提示“可能需要刷新”；
- 以播放器实际错误为准：403/404/超时/解码失败/无进度；
- 每次都重新触发官方页面取流，不复用旧 URL；
- 刷新失败时给用户明确提示，不无限快速重试；
- 连续失败要指数退避，避免给官方服务造成压力。

