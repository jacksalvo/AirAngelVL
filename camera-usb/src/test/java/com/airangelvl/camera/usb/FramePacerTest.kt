package com.airangelvl.camera.usb

import org.junit.Assert.*
import org.junit.Test

class FramePacerTest {
    @Test fun slowInputRetainsWallClockDuration() {
        val clock = FramePacer(30)
        assertEquals(0L, clock.presentationTimeNs(1_000_000_000L))
        assertEquals(200_000_000L, clock.presentationTimeNs(1_200_000_000L))
        assertEquals(2_000_000_000L, clock.presentationTimeNs(3_000_000_000L))
    }

    @Test fun encoderRateLimitDropsFramesWithoutRenumberingTime() {
        val clock = FramePacer(15)
        assertEquals(0L, clock.presentationTimeNs(1_000_000_000L))
        assertNull(clock.presentationTimeNs(1_030_000_000L))
        assertEquals(70_000_000L, clock.presentationTimeNs(1_070_000_000L))
        assertNull(clock.presentationTimeNs(1_060_000_000L))
        assertEquals(1_000_000_000L, clock.presentationTimeNs(2_000_000_000L))
    }

    @Test fun smallCameraJitterDoesNotHalveFrameRate() {
        val clock = FramePacer(30)
        val timestamps = listOf(0L, 32_500_000L, 66_000_000L, 99_000_000L, 132_000_000L, 167_000_000L)
        assertEquals(timestamps, timestamps.map { clock.presentationTimeNs(1_000_000_000L + it) })
    }
}
