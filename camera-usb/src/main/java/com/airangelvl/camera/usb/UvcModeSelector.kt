package com.airangelvl.camera.usb

internal data class UvcMode(val width: Int, val height: Int, val format: Int, val fps: Int = 0) {
    val area: Long get() = width.toLong() * height
}

/** Uses only descriptor-advertised dimensions; 0 fps means let libuvc negotiate 1..30. */
internal object UvcModeSelector {
    const val YUYV = 0
    const val MJPEG = 1

    fun rank(advertised: List<UvcMode>, descriptors: ByteArray): List<UvcMode> {
        val rates = parseFrameRates(descriptors)
        return advertised.filter { it.width in 2..8192 && it.height in 2..8192 }
            .distinctBy { Triple(it.width, it.height, it.format) }
            .sortedWith(compareBy<UvcMode> { it.format != MJPEG }
                .thenBy { it.area > if (it.format == MJPEG) 1280L * 720 else 640L * 480 }
                .thenBy { if (it.area <= if (it.format == MJPEG) 1280L * 720 else 640L * 480) -it.area else it.area })
            .flatMap { size ->
                val supported = rates[Triple(size.width, size.height, size.format)].orEmpty()
                    .filter { it in 1..60 }.distinct()
                    .sortedWith(compareBy<Int> { it > 30 }.thenBy { if (it <= 30) -it else it })
                if (supported.isEmpty()) listOf(size.copy(fps = 0))
                else supported.map { size.copy(fps = it) }
            }
    }

    /** UVC 1.x VS frame descriptors, tolerating truncated/malformed descriptor blobs. */
    fun parseFrameRates(bytes: ByteArray): Map<Triple<Int, Int, Int>, List<Int>> {
        val result = mutableMapOf<Triple<Int, Int, Int>, MutableList<Int>>()
        var offset = 0
        var videoStreaming = false
        fun u8(i: Int) = bytes[i].toInt() and 255
        fun u16(i: Int) = u8(i) or (u8(i + 1) shl 8)
        fun u32(i: Int): Long = (0..3).sumOf { u8(i + it).toLong() shl (it * 8) }
        while (offset + 2 <= bytes.size) {
            val length = u8(offset)
            if (length < 2 || offset + length > bytes.size) break
            val type = u8(offset + 1)
            if (type == 4 && length >= 9) {
                videoStreaming = u8(offset + 5) == 14 && u8(offset + 6) == 2
            }
            if (videoStreaming && type == 0x24 && length >= 26) {
                val subtype = u8(offset + 2)
                val format = when (subtype) { 5 -> YUYV; 7 -> MJPEG; else -> -1 }
                if (format >= 0) {
                    val key = Triple(u16(offset + 5), u16(offset + 7), format)
                    val fps = result.getOrPut(key) { mutableListOf() }
                    val count = u8(offset + 25)
                    if (count > 0) {
                        repeat(minOf(count, (length - 26) / 4)) { index ->
                            val interval = u32(offset + 26 + index * 4)
                            if (interval > 0) fps += (10_000_000L / interval).toInt()
                        }
                    } else if (length >= 38) {
                        val min = u32(offset + 26)
                        val max = u32(offset + 30)
                        val step = u32(offset + 34)
                        for (rate in 1..60) {
                            val interval = 10_000_000L / rate
                            if (interval in min..max && (step == 0L || (interval - min) % step == 0L)) fps += rate
                        }
                    }
                }
            }
            offset += length
        }
        return result
    }
}
