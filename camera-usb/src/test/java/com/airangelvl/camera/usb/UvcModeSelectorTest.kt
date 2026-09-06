package com.airangelvl.camera.usb

import org.junit.Assert.*
import org.junit.Test

class UvcModeSelectorTest {
    @Test fun capabilitiesRemainSeparateByTransportAndPreferConservativeDimensions() {
        val input = listOf(UvcMode(1920, 1080, 1), UvcMode(640, 480, 0), UvcMode(1280, 720, 1), UvcMode(800, 600, 0))
        val ranked = UvcModeSelector.rank(input, byteArrayOf())
        assertEquals(UvcMode(1280, 720, 1), ranked.first())
        assertEquals(input.toSet(), ranked.toSet())
        assertTrue(ranked.indexOf(UvcMode(640, 480, 0)) < ranked.indexOf(UvcMode(800, 600, 0)))
    }

    @Test fun descriptorAdvertisedFiveFpsIsNotExcluded() {
        val bytes = streamingInterface() + frameDescriptor(subtype = 7, intervals = longArrayOf(333333, 2_000_000))
        val modes = UvcModeSelector.rank(listOf(UvcMode(640, 480, 1)), bytes)
        assertEquals(listOf(30, 5), modes.map { it.fps })
    }

    @Test fun sixtyFpsOnlyCameraCanStillPreviewWhileRecordingIsPacedSeparately() {
        val bytes = streamingInterface() + frameDescriptor(subtype = 7, intervals = longArrayOf(166666))
        assertEquals(listOf(60), UvcModeSelector.rank(listOf(UvcMode(640, 480, 1)), bytes).map { it.fps })
    }

    @Test fun malformedDescriptorsStopParsingWithoutReadingOutOfBounds() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0, 4), byteArrayOf(40, 0x24, 7),
            streamingInterface() + byteArrayOf(26, 0x24, 7))) {
            assertTrue(UvcModeSelector.parseFrameRates(bytes).isEmpty())
        }
    }

    @Test fun cameraFormatDescriptorsOutsideStreamingInterfaceAreIgnored() {
        val bytes = frameDescriptor(7, longArrayOf(2_000_000))
        assertTrue(UvcModeSelector.parseFrameRates(bytes).isEmpty())
    }

    private fun streamingInterface() = byteArrayOf(9, 4, 1, 0, 1, 14, 2, 0, 0)
    private fun frameDescriptor(subtype: Int, intervals: LongArray): ByteArray = ByteArray(26 + intervals.size * 4).apply {
        this[0] = size.toByte(); this[1] = 0x24; this[2] = subtype.toByte()
        this[5] = 0x80.toByte(); this[6] = 2; this[7] = 0xE0.toByte(); this[8] = 1
        this[25] = intervals.size.toByte()
        intervals.forEachIndexed { index, interval ->
            repeat(4) { put -> this[26 + index * 4 + put] = (interval shr (put * 8)).toByte() }
        }
    }
}
