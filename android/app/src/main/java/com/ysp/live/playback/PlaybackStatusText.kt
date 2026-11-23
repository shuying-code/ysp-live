package com.ysp.live.playback

import android.content.Context
import com.ysp.live.R

/**
 * Playback routing still uses the canonical status strings emitted by the
 * player and authentication bridge. This object only translates those strings
 * at the UI boundary, so changing the system language cannot break routing.
 */
object PlaybackStatusText {
    fun forDisplay(context: Context, message: String): String {
        val text = message.trim()
        if (text.isEmpty()) return message

        return when {
            text == "正在准备 libmpv" -> context.getString(R.string.notification_preparing_mpv)
            text == "等待从应用发起播放" -> context.getString(R.string.notification_waiting_for_app)
            text == "播放已停止" -> context.getString(R.string.status_playback_stopped)
            text == "播放结束，可重新鉴权" -> context.getString(R.string.status_playback_ended_reauth)
            text == "播放服务已断开" -> context.getString(R.string.status_player_service_disconnected)
            text == "无法启动前台播放服务" -> context.getString(R.string.status_player_service_start_failed)
            text == "播放服务绑定失败" -> context.getString(R.string.status_player_service_bind_failed)
            text == "官方鉴权页已加载" || text == "官方鉴权页已加载，等待播放器取流" ->
                context.getString(R.string.status_auth_page_loaded)
            text == "央视备选页已加载" -> context.getString(R.string.status_cctv_page_loaded)
            text == "官方鉴权桥已注入" -> context.getString(R.string.status_auth_bridge_ready)
            text == "正在加载官方播放器" -> context.getString(R.string.status_loading_official_player)
            text == "正在加载央视备选线路" -> context.getString(R.string.status_loading_cctv_fallback)
            text == "央视备选线路重连中" -> context.getString(R.string.status_reconnecting_cctv)
            text == "官方播放器返回了不受支持的播放地址" -> context.getString(R.string.status_unsupported_stream)
            text == "鉴权消息解析失败" -> context.getString(R.string.status_auth_parse_failed)
            text == "官方播放器播放失败" -> context.getString(R.string.status_official_playback_failed)
            text == "网络错误" -> context.getString(R.string.status_network_error)
            text == "播放异常，正在重新连接" -> context.getString(R.string.status_reconnecting_overlay)
            text == "已停止，可选择频道重新播放" -> context.getString(R.string.status_stopped_overlay)
            text == "央视备选网络中断，准备重连" -> context.getString(R.string.status_fallback_network_overlay)
            text == "播放失败，正在降低清晰度" -> context.getString(R.string.status_lowering_quality_overlay)
            text == "播放失败，请重新选择频道" -> context.getString(R.string.status_playback_failed_overlay)
            text == "没有可用的更低清晰度" -> context.getString(R.string.status_no_lower_quality)
            text == "播放失败 · 央视备选线路未恢复" -> context.getString(R.string.status_fallback_unrecovered)
            text == "备用线路缓冲中" -> context.getString(R.string.status_buffering_backup)
            text == "央视备选缓冲中" -> context.getString(R.string.status_buffering_cctv)
            text == "官方播放器缓冲中" -> context.getString(R.string.status_buffering_official)
            text == "正在播放 · 备用线路" -> context.getString(R.string.status_playing_backup)
            text == "正在播放 · 央视备选" -> context.getString(R.string.status_playing_cctv_fallback)
            text == "正在播放 · 官方播放器" -> context.getString(R.string.status_playing_official)
            text == "libmpv 初始化失败，请检查 ABI 和原生库" -> context.getString(R.string.mpv_init_failed)
            text == "无法绑定视频 Surface" -> context.getString(R.string.mpv_surface_failed)
            text == "播放器尚未初始化" -> context.getString(R.string.mpv_not_ready)
            text == "未知错误" -> context.getString(R.string.mpv_unknown_error)
            text.startsWith("正在切换 · ") ->
                context.getString(R.string.status_switching_detail, text.after("正在切换 · "))
            text.startsWith("正在鉴权 · ") ->
                context.getString(R.string.status_authenticating_detail, text.after("正在鉴权 · "))
            text.startsWith("正在连接 · 官方播放器") -> context.getString(R.string.status_connecting_official)
            text.startsWith("正在连接 · ") && text.endsWith(" · 备用线路") -> {
                val channel = text
                    .removePrefix("正在连接 · ")
                    .removeSuffix(" · 备用线路")
                    .trim()
                context.getString(R.string.status_connecting_backup_detail, channel)
            }
            text.startsWith("正在连接 · ") ->
                context.getString(
                    R.string.status_connecting_detail,
                    context.localizedDetail(text.after("正在连接 · ")),
                )
            text.startsWith("原生播放 · ") ->
                context.getString(
                    R.string.status_native_playback_detail,
                    context.localizedDetail(text.after("原生播放 · ")),
                )
            text.startsWith("正在加载央视备选线路 · ") ->
                context.getString(R.string.status_loading_cctv_detail, text.after("正在加载央视备选线路 · "))
            text.startsWith("正在切换备用线路 · ") ->
                context.getString(R.string.status_switching_backup_detail, text.after("正在切换备用线路 · "))
            text.startsWith("备选线路 · ") ->
                context.getString(R.string.status_fallback_detail, text.after("备选线路 · "))
            text.startsWith("备用线路 · ") ->
                fallbackAttempt(context, text.after("备用线路 · "))
            text.startsWith("正在播放 · ") ->
                context.getString(
                    R.string.status_playing_detail,
                    context.localizedDetail(text.after("正在播放 · ")),
                )
            text.startsWith("正在缓冲 · ") ->
                context.getString(R.string.status_buffering_detail, text.after("正在缓冲 · "))
            text.startsWith("缓冲中 · ") ->
                context.getString(R.string.status_buffering_detail, text.after("缓冲中 · "))
            text.startsWith("正在恢复直播 · ") ->
                context.getString(R.string.status_resuming_live_detail, text.after("正在恢复直播 · "))
            text.startsWith("播放异常，正在重新鉴权 · ") ->
                context.getString(
                    R.string.status_reconnecting_auth_detail,
                    text.after("播放异常，正在重新鉴权 · "),
                )
            text.startsWith("已停止 · ") && text.endsWith(" 连续恢复失败") -> {
                val channel = text
                    .removePrefix("已停止 · ")
                    .removeSuffix(" 连续恢复失败")
                    .trim()
                context.getString(R.string.status_stopped_recovery_detail, channel)
            }
            text.startsWith("央视备选重连中 · ") ->
                reconnectingFallback(context, text.after("央视备选重连中 · "))
            text.startsWith("鉴权页加载失败：") ->
                context.getString(
                    R.string.status_auth_page_failed_detail,
                    context.localizedDetail(text.after("鉴权页加载失败：")),
                )
            text.startsWith("央视备选页加载失败：") ->
                context.getString(
                    R.string.status_cctv_page_failed_detail,
                    context.localizedDetail(text.after("央视备选页加载失败：")),
                )
            text.startsWith("鉴权页错误：") ->
                context.getString(
                    R.string.status_auth_page_error_detail,
                    text.after("鉴权页错误："),
                )
            text.startsWith("正在切换清晰度 · ") ->
                context.getString(
                    R.string.status_switching_quality_detail,
                    context.localizedQualityLabel(text.after("正在切换清晰度 · ")),
                )
            text.startsWith("默认使用最高清晰度 · ") ->
                context.getString(
                    R.string.status_quality_default_detail,
                    context.localizedQualityLabel(text.after("默认使用最高清晰度 · ")),
                )
            text.startsWith("直播卡顿，降低清晰度 · ") ->
                context.getString(
                    R.string.status_quality_stutter_detail,
                    context.localizedQualityLabel(text.after("直播卡顿，降低清晰度 · ")),
                )
            text.startsWith("播放失败，降低清晰度 · ") ->
                context.getString(
                    R.string.status_quality_failure_detail,
                    context.localizedQualityLabel(text.after("播放失败，降低清晰度 · ")),
                )
            text.startsWith("清晰度不可用 · ") ->
                context.getString(
                    R.string.status_quality_unavailable_detail,
                    context.localizedQualityLabel(text.after("清晰度不可用 · ")),
                )
            text.startsWith("清晰度请求超时 · ") ->
                context.getString(
                    R.string.status_quality_timeout_detail,
                    context.localizedQualityLabel(text.after("清晰度请求超时 · ")),
                )
            text.endsWith(" · 清晰度切换失败") -> {
                val channel = text.removeSuffix(" · 清晰度切换失败").trim()
                context.getString(R.string.status_quality_switch_failed_detail, channel)
            }
            text.startsWith("播放失败 · ") ->
                context.getString(R.string.status_playback_failed_detail, text.after("播放失败 · "))
            text.startsWith("播放结束 · ") ->
                context.getString(R.string.status_playback_ended_detail, text.after("播放结束 · "))
            text.startsWith("播放错误：") ->
                context.getString(
                    R.string.status_playback_error_detail,
                    context.localizedDetail(text.after("播放错误：")),
                )
            text.startsWith("播放错误 · ") ->
                context.getString(
                    R.string.status_playback_error_detail,
                    context.localizedDetail(text.after("播放错误 · ")),
                )
            text.startsWith("载入直播流失败：") ->
                context.getString(
                    R.string.mpv_load_stream_failed_detail,
                    context.localizedDetail(text.after("载入直播流失败：")),
                )
            text.startsWith("正在播放") -> context.getString(R.string.status_playing_official)
            else -> message
        }
    }

    private fun fallbackAttempt(context: Context, value: String): String {
        val parts = value.split(" · ", limit = 2)
        val counter = parts.getOrNull(1)?.split('/')
        val index = counter?.getOrNull(0)?.toIntOrNull()
        val total = counter?.getOrNull(1)?.toIntOrNull()
        return if (parts.size == 2 && index != null && total != null) {
            context.getString(
                R.string.status_fallback_attempt_detail,
                parts[0],
                index,
                total,
            )
        } else {
            context.getString(R.string.status_fallback_detail, value)
        }
    }

    private fun reconnectingFallback(context: Context, value: String): String {
        val parts = value.split(" · ", limit = 2)
        val counter = parts.getOrNull(1)?.split('/')
        val index = counter?.getOrNull(0)?.toIntOrNull()
        val total = counter?.getOrNull(1)?.toIntOrNull()
        return if (parts.size == 2 && index != null && total != null) {
            context.getString(
                R.string.status_fallback_reconnecting_detail,
                parts[0],
                index,
                total,
            )
        } else {
            context.getString(R.string.status_reconnecting_cctv)
        }
    }

    private fun String.after(prefix: String): String = removePrefix(prefix).trim()

    private fun Context.localizedDetail(value: String): String = when (value.trim()) {
        "网络错误" -> getString(R.string.status_network_error)
        "未知错误" -> getString(R.string.mpv_unknown_error)
        "直播" -> getString(R.string.live)
        else -> localizedQualityLabel(value.trim())
    }

    private fun Context.localizedQualityLabel(value: String): String {
        var result = value
        listOf(
            "超高清" to R.string.quality_ultra_hd,
            "蓝光" to R.string.quality_blu_ray,
            "超清" to R.string.quality_super_hd,
            "高清" to R.string.quality_hd,
            "标清" to R.string.quality_sd,
            "当前清晰度" to R.string.quality_current,
        ).forEach { (source, resource) ->
            result = result.replace(source, getString(resource))
        }
        return result
    }
}
