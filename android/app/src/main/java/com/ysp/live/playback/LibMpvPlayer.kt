package com.ysp.live.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
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
            setOption("hwdec", "auto-safe")
            setOption("cache", "yes")
            setOption("cache-secs", "20")
            setOption("demuxer-max-bytes", "256MiB")
            setOption("network-timeout", "20")
            setOption("user-agent", USER_AGENT)
            setOption("http-header-fields", "Referer: https://yangshipin.cn/")

            val result = MpvNative.initialize(handle)
            check(result >= 0) {
                "mpv_initialize 失败: ${MpvNative.errorString(result)}"
            }

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

    fun attachSurface(surface: Surface) {
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
            MpvNative.setOptionString(handle, "force-window", "yes")
            MpvNative.setPropertyString(handle, "vo", "gpu")
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
        loaded = true
        val result = synchronized(lock) {
            MpvNative.command(handle, arrayOf("loadfile", url, "replace"))
        }
        if (result < 0) {
            loaded = false
            val reason = runCatching { MpvNative.errorString(result) }
                .getOrDefault("未知错误")
            listener.onError("载入直播流失败：$reason")
            return
        }
        synchronized(lock) {
            MpvNative.setPropertyString(handle, "pause", "no")
        }
        reportStatus("正在连接 · $title")
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
        synchronized(lock) {
            if (handle != 0L) {
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
        var tick = 0
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
                7 -> {
                    loaded = false
                    if (error < 0) {
                        val reason = runCatching { MpvNative.errorString(error) }
                            .getOrDefault("播放结束")
                        mainHandler.post { listener.onError(reason) }
                    } else {
                        mainHandler.post { listener.onEnded() }
                    }
                }
                8 -> {
                    reportStatus("正在播放 · $currentTitle")
                }
            }

            tick += 1
            if (tick % 2 == 0) {
                inspectState()
            }
        }
    }

    private fun inspectState() {
        if (handle == 0L || closing) return
        val pausedForCache = property("paused-for-cache")
        if (pausedForCache == "yes") {
            reportStatus("缓冲中 · $currentTitle")
            return
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
    }
}
