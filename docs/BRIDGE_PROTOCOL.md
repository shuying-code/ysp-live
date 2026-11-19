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
  ]
}
```

约束：

- `playurl` 必须是 HTTPS、主机非空、路径以 `.m3u8` 结尾；
- 不允许 `javascript:`、`file:`、`data:`、`blob:`；
- 不把 `vkey`、`ysign`、`token`、完整 query 或完整路径写入日志；
- `formats` 只保留公开清晰度元数据；
- 同一个 URL 在同一页面生命周期内只上报一次。

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

宿主不需要向页面发送鉴权指令。需要重新鉴权时，由宿主重新导航到：

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

## 7. 刷新策略

- `ysp-core` 的默认租约是 2 小时，仅用于提示“可能需要刷新”；
- 以播放器实际错误为准：403/404/超时/解码失败/无进度；
- 每次都重新触发官方页面取流，不复用旧 URL；
- 刷新失败时给用户明确提示，不无限快速重试；
- 连续失败要指数退避，避免给官方服务造成压力。

