package com.airangelvl.camera.usb

import com.airangelvl.core.camera.RenderSettings
import kotlin.math.cos
import kotlin.math.sin

/** Pure geometry shared by all output surfaces, also testable without a device. */
internal object RenderGeometry {
    fun outputSize(width: Int, height: Int, settings: RenderSettings): Pair<Int, Int> {
        val rotated = settings.rotationDegrees % 180 != 0
        val w = if (rotated) height else width
        val h = if (rotated) width else height
        val ratio = settings.aspectRatio ?: (w.toDouble() / h)
        val outW = minOf(w.toDouble(), h * ratio).toInt().coerceAtLeast(2)
        val outH = minOf(h.toDouble(), w / ratio).toInt().coerceAtLeast(2)
        // Keep photo readback and encoding within a 720p canvas even when a camera only
        // advertises a larger native mode. A readback needs several pixel buffers at once.
        val maxWidth = if (outW >= outH) 1280 else 720
        val maxHeight = if (outW >= outH) 720 else 1280
        val scale = minOf(1.0, maxWidth.toDouble() / outW, maxHeight.toDouble() / outH)
        return ((outW * scale).toInt() / 2 * 2).coerceAtLeast(2) to
            ((outH * scale).toInt() / 2 * 2).coerceAtLeast(2)
    }

    /** Column-major normalized texture matrix: center crop, mirror, then inverse rotation. */
    fun textureMatrix(width: Int, height: Int, settings: RenderSettings, outputRatio: Double): FloatArray {
        val rotated = settings.rotationDegrees % 180 != 0
        val sourceRatio = if (rotated) height.toDouble() / width else width.toDouble() / height
        val sx = minOf(1.0, outputRatio / sourceRatio) * if (settings.mirrorHorizontal) -1.0 else 1.0
        val sy = minOf(1.0, sourceRatio / outputRatio)
        val angle = Math.toRadians(settings.rotationDegrees.toDouble())
        val c = cos(angle)
        val s = sin(angle)
        val a = c * sx
        val b = -s * sy
        val d = s * sx
        val e = c * sy
        return floatArrayOf(a.toFloat(), d.toFloat(), 0f, b.toFloat(), e.toFloat(), 0f,
            (0.5 - (a + b) * 0.5).toFloat(), (0.5 - (d + e) * 0.5).toFloat(), 1f)
    }

    fun fittedViewport(width: Int, height: Int, ratio: Double): IntArray {
        val w = minOf(width.toDouble(), height * ratio).toInt().coerceAtLeast(1)
        val h = minOf(height.toDouble(), width / ratio).toInt().coerceAtLeast(1)
        return intArrayOf((width - w) / 2, (height - h) / 2, w, h)
    }
}
