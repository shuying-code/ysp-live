package com.ysp.live.data

import android.content.Context
import android.util.Log
import com.ysp.live.NativeCore
import org.json.JSONArray

enum class ChannelKind(val label: String) {
    CCTV("央视"),
    SATELLITE("卫视"),
    OTHER("其他"),
}

data class Channel(
    val name: String,
    val pid: String,
    val streamId: String,
    val kind: ChannelKind,
    val vip: Boolean,
    val limitedFree: Boolean,
    val is4K: Boolean,
) {
    val badge: String?
        get() = when {
            limitedFree -> "限免"
            vip -> "VIP"
            else -> null
        }

    val displayName: String
        get() = badge?.let { "$name  [$it]" } ?: name

    val pageUrl: String
        get() = "https://yangshipin.cn/tv/home?pid=$pid"
}

object ChannelRepository {
    const val DEFAULT_PID = "600152137"

    fun load(context: Context): List<Channel> {
        val json = runCatching {
            context.assets.open("channels.json").bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            Log.e("YspLive", "读取频道资产失败", error)
            return fallbackChannels()
        }

        if (NativeCore.isNativeLoaded) {
            val nativeCount = NativeCore.channelCount(json)
            if (nativeCount <= 0) {
                Log.w("YspLive", "Rust 核心未接受频道目录，使用 Kotlin 解析")
            }
        }

        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val rawType = item.optString("channelType")
                    add(
                        Channel(
                            name = item.getString("channelName"),
                            pid = item.getString("pid"),
                            streamId = item.optString("streamId"),
                            kind = when (rawType) {
                                "yangshi", "cctv" -> ChannelKind.CCTV
                                "weishi", "satellite" -> ChannelKind.SATELLITE
                                else -> ChannelKind.OTHER
                            },
                            vip = item.optBoolean("isVip"),
                            limitedFree = item.optBoolean("isLimitedFree"),
                            is4K = item.optBoolean("is4K"),
                        )
                    )
                }
            }
        }.getOrElse { error ->
            Log.e("YspLive", "解析频道目录失败", error)
            fallbackChannels()
        }
    }

    fun fallbackChannels(): List<Channel> = listOf(
        Channel("CCTV1", "600001859", "2024078201", ChannelKind.CCTV, false, false, false),
        Channel("CCTV2", "600001800", "2024075401", ChannelKind.CCTV, false, false, false),
        Channel("北京卫视", "600002309", "2024052703", ChannelKind.SATELLITE, false, false, false),
        Channel("天津卫视", DEFAULT_PID, "2019927003", ChannelKind.SATELLITE, false, false, false),
    )
}

