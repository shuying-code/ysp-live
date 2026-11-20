package com.ysp.live.data

import android.content.Context
import android.util.Log
import org.json.JSONArray

enum class ChannelKind {
    CCTV,
    SATELLITE,
    OTHER,
}

data class Channel(
    val name: String,
    val pid: String,
    val kind: ChannelKind,
)

object ChannelRepository {
    const val DEFAULT_PID = "600152137"
    private val NATIVE_VERIFIED_PIDS = setOf(DEFAULT_PID)

    fun supportsNativePlayback(channel: Channel): Boolean =
        channel.kind == ChannelKind.SATELLITE && channel.pid in NATIVE_VERIFIED_PIDS

    fun load(context: Context): List<Channel> {
        val json = runCatching {
            context.assets.open("channels.json").bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            Log.e("YspLive", "读取频道资产失败", error)
            return fallbackChannels()
        }

        return runCatching {
            val array = JSONArray(json)
            buildList(array.length()) {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    if (item.optBoolean("isVip") || item.optString("payType") == "880") {
                        continue
                    }

                    val rawType = item.optString("channelType")
                    add(
                        Channel(
                            name = item.getString("channelName"),
                            pid = item.getString("pid"),
                            kind = when (rawType) {
                                "yangshi", "cctv" -> ChannelKind.CCTV
                                "weishi", "satellite" -> ChannelKind.SATELLITE
                                else -> ChannelKind.OTHER
                            },
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
        Channel("CCTV1", "600001859", ChannelKind.CCTV),
        Channel("CCTV2", "600001800", ChannelKind.CCTV),
        Channel("北京卫视", "600002309", ChannelKind.SATELLITE),
        Channel("天津卫视", DEFAULT_PID, ChannelKind.SATELLITE),
    )
}

