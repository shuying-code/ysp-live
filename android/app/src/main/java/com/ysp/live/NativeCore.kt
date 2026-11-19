package com.ysp.live

import org.json.JSONArray
import java.net.URI

/**
 * Optional JNI facade. The Android host remains usable when the Rust .so has
 * not been built yet, so UI/WebView work can be developed independently.
 */
object NativeCore {
    private val nativeLoaded: Boolean = runCatching {
        System.loadLibrary("ysp_core_android")
    }.isSuccess

    val isNativeLoaded: Boolean
        get() = nativeLoaded

    @JvmStatic
    private external fun nativeVersion(): String

    @JvmStatic
    private external fun nativeChannelCount(json: String): Int

    @JvmStatic
    private external fun nativeValidatePlayUrl(url: String): Boolean

    @JvmStatic
    private external fun nativeEpgUrl(pid: String, date: String): String?

    fun version(): String {
        if (!nativeLoaded) return "kotlin-fallback"
        return runCatching { nativeVersion() }.getOrDefault("jni-error")
    }

    fun channelCount(json: String): Int {
        if (nativeLoaded) {
            runCatching { nativeChannelCount(json) }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        return runCatching { JSONArray(json).length() }.getOrDefault(0)
    }

    fun validatePlayUrl(url: String): Boolean {
        if (!nativeLoaded) return kotlinValidatePlayUrl(url)
        return runCatching { nativeValidatePlayUrl(url) }
            .getOrDefault(kotlinValidatePlayUrl(url))
    }

    fun epgUrl(pid: String, date: String): String? {
        if (nativeLoaded) {
            runCatching { nativeEpgUrl(pid, date) }.getOrNull()?.let { return it }
        }
        if (!pid.all(Char::isDigit) || date.length != 8 || !date.all(Char::isDigit)) {
            return null
        }
        return "https://capi.yangshipin.cn/api/yspepg/program/$pid/$date"
    }

    private fun kotlinValidatePlayUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            uri.path.orEmpty().lowercase().endsWith(".m3u8")
    }.getOrDefault(false)
}

