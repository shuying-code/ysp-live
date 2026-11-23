package com.ysp.live.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.ysp.live.R
import com.ysp.live.playback.NotificationChannels
import com.ysp.live.playback.PlaybackService
import com.ysp.live.ui.LaunchTarget

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> Unit
            else -> return
        }

        val preferences = context.getSharedPreferences("ysp_live", Context.MODE_PRIVATE)
        if (!preferences.getBoolean("boot_autostart", true)) return

        NotificationChannels.ensure(context)

        // Normal Android apps cannot guarantee a background Activity launch.
        // Try it for TV/OEM builds, then fall back to a foreground service and
        // a user-visible notification. A dedicated TV can be configured as the
        // default HOME app (kiosk flavor) for a reliable boot path.
        val activityIntent = Intent(context, LaunchTarget.activityClass(context)).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }
        val activityStarted = runCatching { context.startActivity(activityIntent) }.isSuccess
        if (!activityStarted) {
            Log.i("YspLive", "系统拒绝后台启动 Activity，使用通知回退")
        }

        val serviceIntent = Intent(context, PlaybackService::class.java)
            .setAction(PlaybackService.ACTION_WAITING)
        val serviceStarted = runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }.isSuccess

        if (!activityStarted && !serviceStarted) {
            NotificationChannels.postBootNotice(
                context,
                context.getString(R.string.notification_boot_fallback),
            )
        }
    }
}

