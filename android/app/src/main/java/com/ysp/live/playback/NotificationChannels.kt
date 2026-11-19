package com.ysp.live.playback

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ysp.live.R
import com.ysp.live.ui.LaunchTarget

object NotificationChannels {
    const val PLAYBACK_CHANNEL = "ysp_live_playback"
    private const val BOOT_CHANNEL = "ysp_live_boot"
    private const val BOOT_NOTIFICATION_ID = 4101

    fun ensure(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                PLAYBACK_CHANNEL,
                "直播播放",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "原生直播播放前台服务"
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                BOOT_CHANNEL,
                "开机启动",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "系统阻止后台启动应用时的提示"
                setShowBadge(true)
            }
        )
    }

    fun postBootNotice(context: Context, text: String) {
        ensure(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(context, LaunchTarget.activityClass(context)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pending = PendingIntent.getActivity(
            context,
            2,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, BOOT_CHANNEL)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(BOOT_NOTIFICATION_ID, notification)
        }
    }
}

