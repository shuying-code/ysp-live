package io.github.yuroyami.libmpvkt.stream

interface MpvStreamProvider {
    fun open(uri: String): MpvStream?
}

interface MpvStream {
    val isSeekable: Boolean

    fun read(into: java.nio.ByteBuffer): Int

    fun seek(offset: Long): Long

    fun size(): Long

    fun close()

    fun cancel() {}
}
