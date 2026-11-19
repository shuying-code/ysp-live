package com.ysp.live.playback

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.ysp.live.R
import com.ysp.live.ui.LaunchTarget

/**
 * Foreground media service. The actual decoding and rendering is libmpv;
 * this class owns the process lifetime, notification and Activity binding.
 */
class PlaybackService : Service(), LibMpvPlayer.Listener {
    companion object {
        const val ACTION_PLAY = "com.ysp.live.action.PLAY"
        const val ACTION_WAITING = "com.ysp.live.action.WAITING"
        const val ACTION_STOP_PLAYBACK = "com.ysp.live.action.STOP_PLAYBACK"
        const val ACTION_STOP_SERVICE = "com.ysp.live.action.STOP_SERVICE"
        const val EXTRA_URL = "com.ysp.live.extra.URL"
        const val EXTRA_TITLE = "com.ysp.live.extra.TITLE"
        const val EXTRA_PID = "com.ysp.live.extra.PID"
        private const val NOTIFICATION_ID = 4100
        private const val TAG = "YspLive"
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var player: LibMpvPlayer

    var currentPid: String? = null
        private set
    var currentTitle: String = "等待播放"
        private set
    var statusListener: ((String) -> Unit)? = null
    var endedListener: (() -> Unit)? = null

    val hasMedia: Boolean
        get() = currentPid != null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        player = LibMpvPlayer(this, this)
        startForegroundCompat(buildNotification("正在准备 libmpv"))
        player.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "直播"
                val pid = intent.getStringExtra(EXTRA_PID)
                if (!url.isNullOrBlank()) {
                    play(url, title, pid)
                }
            }
            ACTION_WAITING -> updateNotification("等待从应用发起播放")
            ACTION_STOP_PLAYBACK -> stopPlayback()
            ACTION_STOP_SERVICE -> {
                stopPlayback()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun play(url: String, title: String, pid: String?) {
        currentPid = pid
        currentTitle = title
        updateNotification("正在连接 $title")
        player.play(url, title, pid)
    }

    fun attachSurface(surface: Surface) {
        if (::player.isInitialized) player.attachSurface(surface)
    }

    fun detachSurface() {
        if (::player.isInitialized) player.detachSurface()
    }

    fun pausePlayback() {
        if (::player.isInitialized) player.pause()
    }

    fun stopPlayback() {
        if (::player.isInitialized) player.stop()
        currentPid = null
        currentTitle = "已停止"
        updateNotification("播放已停止")
    }

    override fun onStatus(text: String) {
        updateNotification(text)
        mainHandler.post { statusListener?.invoke(text) }
    }

    override fun onEnded() {
        currentPid = null
        updateNotification("播放结束 · 可重新鉴权")
        mainHandler.post {
            statusListener?.invoke("播放结束，可重新鉴权")
            endedListener?.invoke()
        }
    }

    override fun onError(text: String) {
        Log.w(TAG, "libmpv: $text")
        currentPid = null
        updateNotification("播放错误 · $text")
        mainHandler.post {
            statusListener?.invoke("播放错误：$text")
            endedListener?.invoke()
        }
    }

    override fun onDestroy() {
        if (::player.isInitialized) player.close()
        statusListener = null
        endedListener = null
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val launchIntent = Intent(this, LaunchTarget.activityClass(this)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopServiceIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP_SERVICE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, NotificationChannels.PLAYBACK_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, "停止", stopServiceIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
