package com.airangelvl.camera.usb

import com.airangelvl.core.camera.RenderSettings
import org.junit.Assert.*
import org.junit.Test

class RenderGeometryTest {
    @Test fun originalAspectIsPreservedAndFittedWithoutStretching() {
        assertEquals(640 to 480, RenderGeometry.outputSize(640, 480, RenderSettings()))
        assertArrayEquals(intArrayOf(0, 240, 640, 480), RenderGeometry.fittedViewport(640, 960, 4.0 / 3))
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            RenderGeometry.textureMatrix(640, 480, RenderSettings(), 4.0 / 3), 0.0001f)
    }

    @Test fun explicitWideAspectCropsTopAndBottomForEveryOutput() {
        val settings = RenderSettings(aspectRatio = 16.0 / 9)
        assertEquals(640 to 360, RenderGeometry.outputSize(640, 480, settings))
        val matrix = RenderGeometry.textureMatrix(640, 480, settings, 16.0 / 9)
        assertEquals(1f, matrix[0], 0.0001f)
        assertEquals(0.75f, matrix[4], 0.0001f)
        assertEquals(0.125f, matrix[7], 0.0001f)
    }

    @Test fun quarterTurnSwapsRasterDimensionsAndMirrorStaysCentered() {
        val settings = RenderSettings(rotationDegrees = 90, mirrorHorizontal = true)
        assertEquals(480 to 640, RenderGeometry.outputSize(640, 480, settings))
        val matrix = RenderGeometry.textureMatrix(640, 480, settings, 3.0 / 4)
        val x = matrix[0] * 0.5f + matrix[3] * 0.5f + matrix[6]
        val y = matrix[1] * 0.5f + matrix[4] * 0.5f + matrix[7]
        assertEquals(0.5f, x, 0.0001f)
        assertEquals(0.5f, y, 0.0001f)
    }

    @Test fun malformedPersistedSettingsCannotBreakGeometry() {
        val normalized = RenderSettings(999, -10, 1000, -90, aspectRatio = Double.NaN).normalized()
        assertEquals(RenderSettings(100, 0, 200, 270), normalized)
    }

    @Test fun largeCameraModesDoNotCreateUnboundedPhotoReadbackBuffers() {
        assertEquals(1280 to 720, RenderGeometry.outputSize(3840, 2160, RenderSettings()))
        assertEquals(720 to 1280, RenderGeometry.outputSize(3840, 2160, RenderSettings(rotationDegrees = 90)))
    }
}
