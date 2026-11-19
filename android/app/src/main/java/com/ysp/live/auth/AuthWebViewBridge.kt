package com.ysp.live.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.ysp.live.NativeCore
import org.json.JSONObject
import java.net.URI

data class AuthStream(
    val url: String,
    val definition: String?,
    val livePid: String?,
    val formatCount: Int,
)

class AuthWebViewBridge(
    private val activity: Activity,
    private val container: ViewGroup,
    private val onStream: (AuthStream) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    private val script: String = activity.assets
        .open("auth_bridge.js")
        .bufferedReader()
        .use { it.readText() }

    private var documentStartSupported = false
    private val webView = WebView(activity)

    init {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.alpha = 0.01f
        webView.isFocusable = false
        webView.isClickable = false
        webView.layoutParams = ViewGroup.LayoutParams(1, 1)
        container.addView(webView)

        configureSettings()
        webView.addJavascriptInterface(AndroidBridge(), "YspAndroidBridge")

        documentStartSupported = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
                true
            } else {
                false
            }
        }.getOrDefault(false)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (!documentStartSupported) {
                    view?.evaluateJavascript(script, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                onStatus("官方鉴权页已加载，等待播放器取流")
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val target = request?.url?.toString() ?: return true
                return !isAllowedPage(target)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    onStatus("鉴权页加载失败：${error?.description ?: "网络错误"}")
                }
            }
        }
    }

    fun load(pid: String) {
        onStatus("正在通过隐藏 WebView 获取官方鉴权")
        webView.loadUrl("https://yangshipin.cn/tv/home?pid=$pid")
    }

    fun reload() {
        onStatus("播放异常，正在重新鉴权")
        webView.reload()
    }

    fun destroy() {
        runCatching { container.removeView(webView) }
        runCatching { webView.stopLoading() }
        runCatching { webView.loadUrl("about:blank") }
        runCatching { webView.clearHistory() }
        runCatching { webView.removeJavascriptInterface("YspAndroidBridge") }
        runCatching { webView.destroy() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureSettings() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        }
    }

    private fun handleMessage(payload: String) {
        val objectValue = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (objectValue.optString("type")) {
            "live_info" -> {
                val url = objectValue.optString("playurl")
                if (!isAllowedStreamUrl(url)) {
                    onStatus("官方播放器返回了不受支持的播放地址")
                    return
                }
                val formats = objectValue.optJSONArray("formats")
                pauseOfficialVideo()
                onStream(
                    AuthStream(
                        url = url,
                        definition = objectValue.optString("defn").ifBlank { null },
                        livePid = objectValue.optString("livepid").ifBlank { null },
                        formatCount = formats?.length() ?: 0,
                    )
                )
            }
            "bridge_ready" -> onStatus("官方鉴权桥已注入")
            "error" -> onStatus("鉴权页错误：${objectValue.optString("message").take(120)}")
        }
    }

    private fun pauseOfficialVideo() {
        webView.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){" +
                "try{v.muted=true;v.pause();}catch(e){}});",
            null,
        )
    }

    private fun isAllowedPage(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        host == "yangshipin.cn" ||
            host.endsWith(".yangshipin.cn") ||
            host == "cctv.com" ||
            host.endsWith(".cctv.com")
    }.getOrDefault(false)

    private fun isAllowedStreamUrl(raw: String): Boolean {
        if (raw.isBlank()) return false
        val structural = runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.path.orEmpty().lowercase().endsWith(".m3u8")
        }.getOrDefault(false)
        if (!structural) return false
        return !NativeCore.isNativeLoaded || NativeCore.validatePlayUrl(raw)
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun postMessage(payload: String) {
            activity.runOnUiThread {
                runCatching { handleMessage(payload) }.onFailure {
                    onStatus("鉴权消息解析失败")
                }
            }
        }
    }
}

