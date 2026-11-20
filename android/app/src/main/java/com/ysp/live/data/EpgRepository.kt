package com.ysp.live.data

import android.util.Log
import com.ysp.live.NativeCore
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

data class EpgProgram(
    val id: String,
    val title: String,
    val startTime: Long,
    val endTime: Long,
    val startLabel: String,
    val endLabel: String,
)

object EpgRepository {
    private const val TAG = "YspLive"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ysp-epg").apply { isDaemon = true }
    }
    private val cache = ConcurrentHashMap<String, List<EpgProgram>>()

    fun load(
        pid: String,
        date: String,
        callback: (Result<List<EpgProgram>>) -> Unit,
    ) {
        val cacheKey = "$pid/$date"
        cache[cacheKey]?.let {
            callback(Result.success(it))
            return
        }
        executor.execute {
            val result = runCatching { fetch(pid, date) }
                .onFailure { error ->
                    Log.w(TAG, "节目单加载失败", error)
                }
                .onSuccess { programs ->
                    cache[cacheKey] = programs
                }
            callback(result)
        }
    }

    private fun fetch(pid: String, date: String): List<EpgProgram> {
        val endpoint = NativeCore.epgUrl(pid, date)
            ?: error("节目单地址无效")
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Referer", "https://yangshipin.cn/")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (connection.responseCode !in 200..299) {
                error("节目单请求失败：HTTP ${connection.responseCode}")
            }
            return parsePrograms(connection.inputStream.use { it.readBytes() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePrograms(data: ByteArray): List<EpgProgram> {
        val reader = ProtoReader(data)
        val programs = ArrayList<EpgProgram>()
        while (reader.hasMore()) {
            val key = reader.readVarint()
            val tag = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            if (tag == 2 && wire == 2) {
                parseProgram(reader.readBytes())?.let(programs::add)
            } else {
                reader.skip(wire)
            }
        }
        return programs.sortedBy(EpgProgram::startTime)
    }

    private fun parseProgram(data: ByteArray): EpgProgram? {
        val reader = ProtoReader(data)
        var id = ""
        var title = ""
        var startTime = 0L
        var endTime = 0L
        var startLabel = ""
        var endLabel = ""

        while (reader.hasMore()) {
            val key = reader.readVarint()
            val tag = (key ushr 3).toInt()
            val wire = (key and 0x7L).toInt()
            when {
                tag == 1 && wire == 2 -> id = reader.readString()
                tag == 2 && wire == 2 -> title = reader.readString()
                tag == 3 && wire == 0 -> startTime = reader.readVarint()
                tag == 4 && wire == 0 -> endTime = reader.readVarint()
                tag == 5 && wire == 2 -> startLabel = reader.readString()
                tag == 6 && wire == 2 -> endLabel = reader.readString()
                else -> reader.skip(wire)
            }
        }

        if (
            title.isBlank() ||
            startTime <= 0L ||
            endTime <= startTime
        ) {
            return null
        }
        return EpgProgram(
            id = id,
            title = title,
            startTime = startTime,
            endTime = endTime,
            startLabel = startLabel,
            endLabel = endLabel,
        )
    }

    private class ProtoReader(private val data: ByteArray) {
        private var position = 0

        fun hasMore(): Boolean = position < data.size

        fun readVarint(): Long {
            var result = 0L
            var shift = 0
            while (shift < 64) {
                if (position >= data.size) error("节目单 protobuf 数据不完整")
                val byte = data[position++].toInt() and 0xFF
                result = result or ((byte and 0x7F).toLong() shl shift)
                if (byte and 0x80 == 0) return result
                shift += 7
            }
            error("节目单 protobuf varint 过长")
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            if (length < 0 || position + length > data.size) {
                error("节目单 protobuf 长度无效")
            }
            return data.copyOfRange(position, position + length).also {
                position += length
            }
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun skip(wire: Int) {
            when (wire) {
                0 -> readVarint()
                1 -> advance(8)
                2 -> advance(readVarint().toInt())
                5 -> advance(4)
                else -> error("不支持的节目单 protobuf 字段类型：$wire")
            }
        }

        private fun advance(length: Int) {
            if (length < 0 || position + length > data.size) {
                error("节目单 protobuf 跳转越界")
            }
            position += length
        }
    }
}
