package com.ysp.live.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.github.yuroyami.libmpvkt.jni.MpvNative
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Small app-owned wrapper around the libmpv client API.
 *
 * The prebuilt `libmpvkt_jni.so` exposes a stable JNI surface in this package.
 * Keeping this wrapper local avoids pulling the full Kotlin multiplatform
 * wrapper and its coroutine dependency into the TV APK.
 */
class LibMpvPlayer(
    context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(text: String)
        fun onEnded()
        fun onError(text: String)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile
    private var handle = 0L

    @Volatile
    private var closing = false

    @Volatile
    private var loaded = false

    @Volatile
    private var currentTitle = "直播"

    private var surfaceHandle = 0L
    private var eventThread: Thread? = null
    private var lastStatus = ""
    private var lastInspectAt = 0L
    private var wasPausedForCache = false
    private var suppressEndEventUntil = 0L

    fun start(): Boolean = synchronized(lock) {
        if (handle != 0L) return true

        val started = runCatching {
            MpvNative.initAndroid(appContext)
            handle = MpvNative.create()
            check(handle != 0L) { "mpv_create 返回空句柄" }

            setOption("osc", "no")
            setOption("input-default-bindings", "no")
            setOption("config", "no")
            setOption("ytdl", "no")
            setOption("keep-open", "no")
            setOption("force-window", "no")
            setOption("keepaspect", "yes")
            setOption("panscan", "0")
            setOption("vo", "null")
            setOption("gpu-context", "android")
            setOption("opengl-es", "yes")
            setOption("hwdec", "auto-safe")
            setOption("cache", "yes")
            setOption("cache-secs", "5")
            setOption("cache-pause", "yes")
            setOption("cache-pause-wait", "1")
            setOption("demuxer-readahead-secs", "2")
            setOption("demuxer-max-bytes", "64MiB")
            setOption("demuxer-max-back-bytes", "16MiB")
            setOption("network-timeout", "20")
            setOption("user-agent", USER_AGENT)
            setOption("http-header-fields", "Referer: https://yangshipin.cn/")

            val result = MpvNative.initialize(handle)
            check(result >= 0) {
                "mpv_initialize 失败: ${MpvNative.errorString(result)}"
            }
            MpvNative.requestLogMessages(handle, "warn")

            closing = false
            eventThread = Thread(::eventLoop, "ysp-mpv-events").apply {
                isDaemon = true
                start()
            }
            true
        }.getOrElse { error ->
            Log.e(TAG, "libmpv 初始化失败", error)
            handle = 0L
            false
        }

        if (!started) {
            listener.onError("libmpv 初始化失败，请检查 ABI 和原生库")
        }
        started
    }

    fun attachSurface(surface: Surface, width: Int, height: Int) {
        synchronized(lock) {
            if (handle == 0L || !surface.isValid) return
            detachSurfaceLocked()
            surfaceHandle = MpvNative.surfaceHandle(surface)
            val result = MpvNative.setOptionNode(
                handle,
                "wid",
                encodeInt64(surfaceHandle),
            )
            if (result < 0) {
                MpvNative.releaseSurfaceHandle(surfaceHandle)
                surfaceHandle = 0L
                listener.onError("无法绑定视频 Surface")
                return
            }
            val forceWindowResult = MpvNative.setOptionString(handle, "force-window", "yes")
            val voResult = MpvNative.setPropertyString(handle, "vo", "gpu")
            val sizeResult = resizeSurfaceLocked(width, height)
            Log.i(
                TAG,
                "绑定 Surface: wid=$result force-window=$forceWindowResult " +
                    "vo=$voResult size=$sizeResult ${surfaceSize(width, height)}",
            )
            logState("surface-attached")
        }
    }

    fun resizeSurface(width: Int, height: Int) {
        synchronized(lock) {
            resizeSurfaceLocked(width, height)
        }
    }

    fun detachSurface() {
        synchronized(lock) {
            detachSurfaceLocked()
        }
    }

    fun play(url: String, title: String, pid: String?) {
        if (handle == 0L) {
            listener.onError("播放器尚未初始化")
            return
        }
        currentTitle = title
        wasPausedForCache = false
        val result = synchronized(lock) {
            if (loaded) {
                suppressEndEventUntil = SystemClock.elapsedRealtime() + 1_500
            }
            MpvNative.command(handle, arrayOf("loadfile", url, "replace"))
        }
        if (result < 0) {
            loaded = false
            val reason = runCatching { MpvNative.errorString(result) }
                .getOrDefault("未知错误")
            listener.onError("载入直播流失败：$reason")
            return
        }
        loaded = true
        synchronized(lock) {
            MpvNative.setPropertyString(handle, "pause", "no")
        }
        reportStatus("正在连接 · $title")
        logState("loadfile")
    }

    fun pause() {
        synchronized(lock) {
            if (handle != 0L) {
                MpvNative.setPropertyString(handle, "pause", "yes")
            }
        }
    }

    fun resume() {
        synchronized(lock) {
            if (handle != 0L) {
                MpvNative.setPropertyString(handle, "pause", "no")
            }
        }
    }

    fun stop() {
        loaded = false
        wasPausedForCache = false
        synchronized(lock) {
            if (handle != 0L) {
                suppressEndEventUntil = SystemClock.elapsedRealtime() + 1_500
                MpvNative.command(handle, arrayOf("stop"))
            }
        }
        reportStatus("播放已停止")
    }

    fun close() {
        val threadToJoin = synchronized(lock) {
            if (handle == 0L) return@synchronized null
            closing = true
            detachSurfaceLocked()
            MpvNative.wakeup(handle)
            eventThread
        }
        threadToJoin?.let { thread ->
            runCatching { thread.join(2_000) }
        }
        synchronized(lock) {
            if (handle != 0L) {
                runCatching { MpvNative.terminateDestroy(handle) }
                handle = 0L
            }
            eventThread = null
        }
    }

    private fun eventLoop() {
        while (!closing && handle != 0L) {
            val packet = runCatching { MpvNative.waitEvent(handle, 0.5) }
                .getOrNull()
            if (packet == null || packet.size < 16) {
                continue
            }

            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
            val eventId = buffer.getInt(0)
            val error = buffer.getInt(12)
            when (eventId) {
                1 -> break
                2 -> logMessage(packet)
                7 -> {
                    if (SystemClock.elapsedRealtime() < suppressEndEventUntil) {
                        suppressEndEventUntil = 0L
                    } else {
                        loaded = false
                        if (error < 0) {
                            val reason = runCatching { MpvNative.errorString(error) }
                                .getOrDefault("播放结束")
                            mainHandler.post { listener.onError(reason) }
                        } else {
                            mainHandler.post { listener.onEnded() }
                        }
                    }
                }
                8 -> {
                    reportStatus("正在播放 · $currentTitle")
                    logState("file-loaded")
                }
            }

            val now = SystemClock.elapsedRealtime()
            if (now - lastInspectAt >= 1_000L) {
                lastInspectAt = now
                inspectState()
            }
        }
    }

    private fun inspectState() {
        if (handle == 0L || closing) return
        val pausedForCache = property("paused-for-cache") == "yes"
        if (pausedForCache) {
            if (!wasPausedForCache) {
                reportStatus("缓冲中 · $currentTitle")
            }
            wasPausedForCache = true
            return
        }
        if (wasPausedForCache) {
            wasPausedForCache = false
            if (loaded) {
                reportStatus("正在播放 · $currentTitle")
            }
        }

        val idle = property("idle-active")
        if (loaded && idle == "yes") {
            loaded = false
            reportStatus("播放结束 · $currentTitle")
        }
    }

    private fun property(name: String): String? =
        runCatching { MpvNative.getPropertyString(handle, name) }.getOrNull()

    private fun setOption(name: String, value: String) {
        val result = MpvNative.setOptionString(handle, name, value)
        if (result < 0) {
            Log.w(TAG, "忽略无效 mpv 选项 $name: ${MpvNative.errorString(result)}")
        }
    }

    private fun detachSurfaceLocked() {
        if (surfaceHandle == 0L) return
        runCatching {
            MpvNative.setPropertyString(handle, "vo", "null")
            MpvNative.setOptionString(handle, "force-window", "no")
            MpvNative.setOptionNode(handle, "wid", encodeInt64(0))
            MpvNative.releaseSurfaceHandle(surfaceHandle)
        }
        surfaceHandle = 0L
    }

    private fun resizeSurfaceLocked(width: Int, height: Int): Int {
        if (handle == 0L || surfaceHandle == 0L || width <= 0 || height <= 0) return -1
        return MpvNative.setPropertyString(
            handle,
            "android-surface-size",
            surfaceSize(width, height),
        )
    }

    private fun logMessage(packet: ByteArray) {
        val values = decodeStringMap(packet) ?: return
        val text = values["text"].orEmpty().trim()
        if (text.isBlank()) return
        val redacted = text.replace(Regex("""https?://\S+"""), "<url>")
        if (KNOWN_NOISE.any { redacted.contains(it) }) return
        val prefix = values["prefix"].orEmpty()
        val level = values["level"].orEmpty()
        Log.i(TAG, "mpv[$level/$prefix] $redacted")
    }

    private fun logState(stage: String) {
        if (handle == 0L || closing) return
        val fields = listOf(
            "video-format",
            "width",
            "height",
            "hwdec-current",
            "estimated-vf-fps",
            "idle-active",
            "paused-for-cache",
        )
        val state = fields.joinToString(" ") { name ->
            "$name=${property(name) ?: "<unavailable>"}"
        }
        Log.i(TAG, "状态[$stage] $state")
    }

    private fun surfaceSize(width: Int, height: Int): String = "${width}x$height"

    private fun decodeStringMap(packet: ByteArray): Map<String, String>? {
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        if (buffer.getInt(0) != 2 || packet.size < 17) return null
        buffer.position(16)
        if (buffer.get().toInt() != 8) return null
        val count = buffer.int
        if (count < 0) return null
        val values = linkedMapOf<String, String>()
        repeat(count) {
            if (buffer.remaining() < 5) return values
            val keyLength = buffer.int
            if (keyLength < 0 || keyLength > buffer.remaining()) return values
            val key = ByteArray(keyLength).also(buffer::get).toString(Charsets.UTF_8)
            if (buffer.remaining() < 1) return values
            val tag = buffer.get().toInt()
            if (tag == 1) {
                if (buffer.remaining() < 4) return values
                val valueLength = buffer.int
                if (valueLength < 0 || valueLength > buffer.remaining()) return values
                values[key] = ByteArray(valueLength).also(buffer::get).toString(Charsets.UTF_8)
            } else {
                return values
            }
        }
        return values
    }

    private fun reportStatus(text: String) {
        if (text == lastStatus) return
        lastStatus = text
        mainHandler.post { listener.onStatus(text) }
    }

    private fun encodeInt64(value: Long): ByteArray =
        ByteBuffer.allocate(9)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(4)
            .putLong(value)
            .array()

    companion object {
        private const val TAG = "YspMpv"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
        private val KNOWN_NOISE = listOf(
            "h264_mediacodec: Both surface and native_window are NULL",
            "Packet corrupt",
        )
    }
}
