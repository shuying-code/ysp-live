package io.github.yuroyami.libmpvkt.jni

import android.content.Context
import android.view.Surface

/**
 * Minimal JNI surface for the prebuilt libmpv Android binaries.
 *
 * The symbol names must match the native library exactly:
 * `Java_io_github_yuroyami_libmpvkt_jni_MpvNative_*`.
 */
object MpvNative {
    init {
        System.loadLibrary("mpv")
        System.loadLibrary("mpvkt_jni")
    }

    external fun initAndroid(appContext: Context)
    external fun create(): Long
    external fun initialize(handle: Long): Int
    external fun terminateDestroy(handle: Long)

    external fun setOptionString(handle: Long, name: String, value: String): Int
    external fun setOptionNode(handle: Long, name: String, value: ByteArray): Int
    external fun command(handle: Long, args: Array<String>): Int
    external fun getPropertyString(handle: Long, name: String): String?
    external fun setPropertyString(handle: Long, name: String, value: String): Int
    external fun waitEvent(handle: Long, timeoutSeconds: Double): ByteArray?
    external fun wakeup(handle: Long)
    external fun errorString(code: Int): String

    external fun surfaceHandle(surface: Surface): Long
    external fun releaseSurfaceHandle(handle: Long)
}
