package com.ysp.live.auth

import com.ysp.live.data.Channel
import com.ysp.live.data.ChannelKind
import java.util.Locale

object CctvLiveFallback {
    private val pagePaths = mapOf(
        "CCTV1" to "cctv1",
        "CCTV2" to "cctv2",
        "CCTV3" to "cctv3",
        "CCTV4" to "cctv4",
        "CCTV5" to "cctv5",
        "CCTV5+" to "cctv5plus",
        "CCTV6" to "cctv6",
        "CCTV7" to "cctv7",
        "CCTV8" to "cctv8",
        "CCTV9" to "cctvjilu",
        "CCTV10" to "cctv10",
        "CCTV11" to "cctv11",
        "CCTV12" to "cctv12",
        "CCTV13" to "cctv13",
        "CCTV14" to "cctvchild",
        "CCTV15" to "cctv15",
        "CCTV16-HD" to "cctv16",
        "CCTV17" to "cctv17",
    )

    fun urlFor(channel: Channel): String? {
        if (channel.kind != ChannelKind.CCTV) return null
        val name = channel.name
            .uppercase(Locale.ROOT)
            .replace('＋', '+')
            .replace('（', '(')
            .replace('）', ')')
            .replace(" ", "")
            .replace("－", "-")
        val path = pagePaths[name] ?: return null
        return "https://tv.cctv.com/live/$path/m/"
    }
}
