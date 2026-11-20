package com.ysp.live.auth

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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
    private enum class PageSource {
        NONE,
        YSP,
        CCTV,
    }

    private val script: String = activity.assets
        .open("auth_bridge.js")
        .bufferedReader()
        .use { it.readText() }

    private var documentStartSupported = false
    private var officialPlayerMode = false
    private var pageSource = PageSource.NONE
    private var fallbackUrl: String? = null
    private var currentPid: String? = null
    private var pageReady = false
    private var loadGeneration = 0L
    private var awaitingChannelStream = false
    private var resumeRunnable: Runnable? = null
    private val webView = WebView(activity)

    init {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.alpha = 0.01f
        webView.visibility = View.INVISIBLE
        webView.isFocusable = false
        webView.isClickable = false
        webView.layoutParams = FrameLayout.LayoutParams(1, 1)
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
                if (pageSource == PageSource.CCTV) {
                    pageReady = true
                    onStatus("央视备选页已加载")
                    if (officialPlayerMode) {
                        resumeOfficialVideo(
                            350,
                            retriesLeft = CCTV_RESUME_RETRIES,
                        )
                    }
                    return
                }
                val finishedPid = pidFromUrl(url)
                if (
                    finishedPid != null &&
                    !currentPid.isNullOrBlank() &&
                    finishedPid != currentPid
                ) {
                    return
                }
                pageReady = true
                onStatus("官方鉴权页已加载，等待播放器取流")
                if (officialPlayerMode) {
                    resumeOfficialVideo(240)
                } else {
                    pauseOfficialVideo()
                }
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
                    if (pageSource == PageSource.CCTV) {
                        pageReady = false
                        onStatus("央视备选页加载失败：${error?.description ?: "网络错误"}")
                    } else {
                        onStatus("鉴权页加载失败：${error?.description ?: "网络错误"}")
                    }
                }
            }
        }
    }

    fun load(pid: String) {
        val generation = ++loadGeneration
        currentPid = pid
        fallbackUrl = null
        awaitingChannelStream = true
        resumeRunnable?.let(webView::removeCallbacks)
        resumeRunnable = null
        webView.onResume()
        pauseOfficialVideo()
        if (!pageReady || pageSource != PageSource.YSP) {
            loadFullPage(pid)
            return
        }
        val quotedPid = JSONObject.quote(pid)
        webView.evaluateJavascript(
            "(function(){try{return !!(window.__yspSwitchOfficialChannel&&" +
                "window.__yspSwitchOfficialChannel($quotedPid));}catch(e){return false;}})()",
        ) { result ->
            if (generation != loadGeneration) return@evaluateJavascript
            if (result == "true") {
                Log.i(TAG, "使用官方页内切换频道")
                onStatus("正在连接 · 官方播放器")
            } else {
                loadFullPage(pid)
            }
        }
    }

    private fun loadFullPage(pid: String) {
        pageSource = PageSource.YSP
        pageReady = false
        awaitingChannelStream = true
        webView.settings.userAgentString = YSP_USER_AGENT
        onStatus("正在加载官方播放器")
        webView.loadUrl("https://yangshipin.cn/tv/home?pid=$pid")
    }

    fun loadCctvFallback(pid: String, url: String) {
        ++loadGeneration
        currentPid = pid
        pageSource = PageSource.CCTV
        fallbackUrl = url
        pageReady = false
        awaitingChannelStream = false
        resumeRunnable?.let(webView::removeCallbacks)
        resumeRunnable = null
        webView.onResume()
        pauseOfficialVideo()
        webView.settings.userAgentString = CCTV_USER_AGENT
        onStatus("正在加载央视备选线路")
        webView.loadUrl(url)
    }

    fun setOfficialPlayerMode(enabled: Boolean) {
        officialPlayerMode = enabled
        webView.alpha = if (enabled) 1f else 0.01f
        webView.visibility = if (enabled) View.VISIBLE else View.INVISIBLE
        webView.isFocusable = enabled
        webView.isClickable = enabled
        webView.layoutParams = if (enabled) {
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        } else {
            FrameLayout.LayoutParams(1, 1)
        }
        if (enabled) {
            webView.onResume()
        } else {
            pauseOfficialVideo()
        }
    }

    fun reload() {
        loadGeneration += 1
        awaitingChannelStream = true
        pageReady = false
        resumeRunnable?.let(webView::removeCallbacks)
        resumeRunnable = null
        webView.onResume()
        if (pageSource == PageSource.CCTV) {
            awaitingChannelStream = false
            onStatus("央视备选线路重连中")
            fallbackUrl?.let(webView::loadUrl) ?: webView.reload()
        } else {
            onStatus("播放异常，正在重新鉴权")
            webView.reload()
        }
    }

    fun destroy() {
        loadGeneration += 1
        resumeRunnable?.let(webView::removeCallbacks)
        resumeRunnable = null
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
            userAgentString = YSP_USER_AGENT
        }
    }

    private fun handleMessage(payload: String) {
        val objectValue = runCatching { JSONObject(payload) }.getOrNull() ?: return
        when (objectValue.optString("type")) {
            "live_info" -> {
                if (pageSource != PageSource.YSP) return
                val url = objectValue.optString("playurl")
                if (!isAllowedStreamUrl(url)) {
                    onStatus("官方播放器返回了不受支持的播放地址")
                    return
                }
                val livePid = objectValue.optString("livepid").ifBlank { null }
                if (
                    livePid != null &&
                    !currentPid.isNullOrBlank() &&
                    livePid != currentPid
                ) {
                    Log.i(TAG, "忽略上一条频道的取流回调")
                    return
                }
                val formats = objectValue.optJSONArray("formats")
                awaitingChannelStream = false
                if (!officialPlayerMode) {
                    pauseOfficialVideo()
                    webView.onPause()
                } else {
                    resumeOfficialVideo(240)
                }
                onStream(
                    AuthStream(
                        url = url,
                        definition = objectValue.optString("defn").ifBlank { null },
                        livePid = livePid,
                        formatCount = formats?.length() ?: 0,
                    )
                )
            }
            "bridge_ready" -> {
                if (pageSource == PageSource.YSP) {
                    onStatus("官方鉴权桥已注入")
                }
            }
            "playing" -> {
                if (officialPlayerMode && !awaitingChannelStream) {
                    onStatus(
                        if (pageSource == PageSource.CCTV) {
                            "正在播放 · 央视备选"
                        } else {
                            "正在播放 · 官方播放器"
                        }
                    )
                }
            }
            "buffering" -> {
                if (officialPlayerMode && !awaitingChannelStream) {
                    onStatus(
                        if (pageSource == PageSource.CCTV) {
                            "央视备选缓冲中"
                        } else {
                            "官方播放器缓冲中"
                        }
                    )
                }
            }
            "error" -> {
                val message = objectValue.optString("message").take(120)
                if (pageSource == PageSource.CCTV) {
                    Log.w(TAG, "忽略央视备选页脚本错误：$message")
                } else if (awaitingChannelStream) {
                    Log.w(TAG, "忽略切台期间的页面脚本错误：$message")
                } else {
                    onStatus("鉴权页错误：$message")
                }
            }
        }
    }

    private fun pauseOfficialVideo() {
        val nativeMode = if (officialPlayerMode) "false" else "true"
        webView.evaluateJavascript(
            "window.__yspPauseOfficialVideo&&window.__yspPauseOfficialVideo();" +
                "window.__yspSetNativeMode&&window.__yspSetNativeMode($nativeMode);" +
                "document.querySelectorAll('video,audio').forEach(function(v){" +
                "try{v.autoplay=false;v.muted=true;v.pause();}catch(e){}});",
            null,
        )
    }

    private fun resumeOfficialVideo(delayMs: Long = 0L, retriesLeft: Int = 1) {
        if (!officialPlayerMode) return
        resumeRunnable?.let(webView::removeCallbacks)
        val generation = loadGeneration
        val runnable = Runnable {
            if (generation != loadGeneration || !officialPlayerMode) return@Runnable
            webView.evaluateJavascript(
                "(function(){try{return !!(window.__yspSetNativeMode&&" +
                    "window.__yspSetNativeMode(false)," +
                    "window.__yspResumeOfficialVideo&&" +
                    "window.__yspResumeOfficialVideo());}catch(e){return false;}})()",
            ) { result ->
                if (generation != loadGeneration || !officialPlayerMode) {
                    return@evaluateJavascript
                }
                if (result != "true" && retriesLeft > 0) {
                    resumeOfficialVideo(
                        if (pageSource == PageSource.CCTV) 800 else 900,
                        retriesLeft - 1,
                    )
                }
            }
        }
        resumeRunnable = runnable
        webView.postDelayed(runnable, delayMs)
    }

    private fun isAllowedPage(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        host == "yangshipin.cn" ||
            host.endsWith(".yangshipin.cn") ||
            host == "cctv.com" ||
            host.endsWith(".cctv.com") ||
            host == "cctv.cn" ||
            host.endsWith(".cctv.cn") ||
            host == "cntv.cn" ||
            host.endsWith(".cntv.cn")
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

    private fun pidFromUrl(raw: String?): String? = runCatching {
        val query = URI(raw).rawQuery ?: return@runCatching null
        query.split('&').firstNotNullOfOrNull { part ->
            val pair = part.split('=', limit = 2)
            if (pair.size == 2 && pair[0] == "pid") pair[1] else null
        }
    }.getOrNull()

    private inner class AndroidBridge {
        @JavascriptInterface
        fun isOfficialPlayerMode(): Boolean = officialPlayerMode

        @JavascriptInterface
        fun postMessage(payload: String) {
            activity.runOnUiThread {
                runCatching { handleMessage(payload) }.onFailure {
                    onStatus("鉴权消息解析失败")
                }
            }
        }
    }

    companion object {
        private const val TAG = "YspLive"
        private const val CCTV_RESUME_RETRIES = 14
        private const val YSP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        private const val CCTV_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 12; M2007J3SC) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

