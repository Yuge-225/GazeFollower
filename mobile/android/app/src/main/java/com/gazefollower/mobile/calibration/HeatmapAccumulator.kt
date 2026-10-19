package com.gazefollower.mobile.calibration

import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min

/** Small, device-friendly Gaussian density grid in normalized screen coordinates. */
class HeatmapAccumulator(
    val width: Int = 72,
    val height: Int = 152,
    private val sigmaFraction: Float = 0.035f,
) {
    private val density = FloatArray(width * height)

    var sampleCount: Int = 0
        private set

    @Synchronized
    fun add(normalizedX: Float, normalizedY: Float) {
        val centerX = normalizedX.coerceIn(0f, 1f) * (width - 1)
        val centerY = normalizedY.coerceIn(0f, 1f) * (height - 1)
        val sigma = min(width, height) * sigmaFraction
        val radius = ceil(sigma * 3f).toInt()
        val left = (centerX.toInt() - radius).coerceAtLeast(0)
        val right = (centerX.toInt() + radius).coerceAtMost(width - 1)
        val top = (centerY.toInt() - radius).coerceAtLeast(0)
        val bottom = (centerY.toInt() + radius).coerceAtMost(height - 1)
        val denominator = 2f * sigma * sigma

        for (y in top..bottom) {
            for (x in left..right) {
                val dx = x - centerX
                val dy = y - centerY
                density[y * width + x] += exp(-((dx * dx + dy * dy) / denominator))
            }
        }
        sampleCount += 1
    }

    @Synchronized
    fun snapshot(): FloatArray = density.clone()

    @Synchronized
    fun clear() {
        density.fill(0f)
        sampleCount = 0
    }
}
