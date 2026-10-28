package com.gazefollower.mobile.experiment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

data class HeatmapPoint(
    val timestampMs: Long,
    val normalizedX: Float,
    val normalizedY: Float,
)

/** Draws the annotated ROI and the cumulative gaze distribution over a replayed video. */
class GazeHeatmapView(context: Context) : View(context) {
    private var points: List<HeatmapPoint> = emptyList()
    private var playbackTimeMs: Long = 0L
    private var currentRoi: NormalizedRoi? = null
    private val density = resources.displayMetrics.density
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF33E58D.toInt()
    }
    private val currentPointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.WHITE
    }

    fun setPoints(value: List<HeatmapPoint>) {
        points = value.sortedBy { it.timestampMs }
        invalidate()
    }

    fun setPlaybackState(timestampMs: Long, roi: NormalizedRoi?) {
        playbackTimeMs = timestampMs
        currentRoi = roi
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        currentRoi?.let { roi ->
            canvas.drawRect(
                roi.left * width,
                roi.top * height,
                roi.right * width,
                roi.bottom * height,
                roiPaint,
            )
        }

        val visible = points.filter { it.timestampMs <= playbackTimeMs }
        val radius = 34f * density
        visible.forEach { point ->
            val x = point.normalizedX.coerceIn(0f, 1f) * width
            val y = point.normalizedY.coerceIn(0f, 1f) * height
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(
                    x,
                    y,
                    radius,
                    intArrayOf(0x70FF2D20, 0x55FFB000, 0x2526D9FF, Color.TRANSPARENT),
                    floatArrayOf(0f, 0.28f, 0.62f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawCircle(x, y, radius, paint)
        }

        visible.lastOrNull()?.let { point ->
            canvas.drawCircle(
                point.normalizedX.coerceIn(0f, 1f) * width,
                point.normalizedY.coerceIn(0f, 1f) * height,
                7f * density,
                currentPointPaint,
            )
        }
    }
}
