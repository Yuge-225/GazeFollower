package com.gazefollower.mobile.calibration

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.gazefollower.mobile.inference.GazeInferenceResult

class GazeCalibrationView(context: Context) : View(context) {
    private enum class State { IDLE, CALIBRATING, CALIBRATED, FAILED }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val button = RectF()
    private val targets = listOf(
        0.5f to 0.5f,
        0.1f to 0.12f, 0.5f to 0.12f, 0.9f to 0.12f,
        0.1f to 0.5f, 0.9f to 0.5f,
        0.1f to 0.88f, 0.5f to 0.88f, 0.9f to 0.88f,
    )
    private val samples = mutableListOf<FloatArray>()
    private val labels = mutableListOf<FloatArray>()
    private var model: RidgeCalibration? = null
    private var state = State.IDLE
    private var pointIndex = 0
    private var warmupFrames = 0
    private var pointSamples = 0
    private var gazePoint: Pair<Float, Float>? = null

    @Synchronized
    fun onGaze(result: GazeInferenceResult) {
        when (state) {
            State.CALIBRATING -> collect(result.features)
            State.CALIBRATED -> {
                val prediction = model?.predict(result.features) ?: return
                gazePoint = prediction[0].coerceIn(0f, 1f) to prediction[1].coerceIn(0f, 1f)
            }
            else -> Unit
        }
        postInvalidateOnAnimation()
    }

    @Synchronized
    private fun collect(features: FloatArray) {
        if (warmupFrames < WARMUP_FRAMES) {
            warmupFrames += 1
            return
        }
        val target = targets[pointIndex]
        samples += features.clone()
        labels += floatArrayOf(target.first, target.second)
        pointSamples += 1
        if (pointSamples < SAMPLES_PER_POINT) return

        pointIndex += 1
        warmupFrames = 0
        pointSamples = 0
        if (pointIndex < targets.size) return
        try {
            model = RidgeCalibration().also { it.fit(samples, labels) }
            state = State.CALIBRATED
        } catch (_: Exception) {
            state = State.FAILED
        }
    }

    @Synchronized
    private fun startCalibration() {
        samples.clear()
        labels.clear()
        model = null
        gazePoint = null
        pointIndex = 0
        warmupFrames = 0
        pointSamples = 0
        state = State.CALIBRATING
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && button.contains(event.x, event.y)) {
            startCalibration()
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        button.set(width * 0.27f, 20f, width * 0.73f, 92f)
        paint.color = 0xCC101216.toInt()
        canvas.drawRoundRect(button, 22f, 22f, paint)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 30f
        paint.color = Color.WHITE
        val buttonText = when (state) {
            State.IDLE -> "Start 9-point calibration"
            State.CALIBRATING -> "Point ${pointIndex + 1}/9"
            State.CALIBRATED -> "Recalibrate"
            State.FAILED -> "Calibration failed — retry"
        }
        canvas.drawText(buttonText, button.centerX(), button.centerY() + 10f, paint)

        if (state == State.CALIBRATING) {
            val target = targets[pointIndex]
            val x = target.first * width
            val y = target.second * height
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, 28f, paint)
            paint.color = Color.RED
            canvas.drawCircle(x, y, 11f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = Color.BLACK
            canvas.drawCircle(x, y, 28f, paint)
            paint.style = Paint.Style.FILL

            paint.textSize = 26f
            paint.color = Color.WHITE
            val progress = if (warmupFrames < WARMUP_FRAMES) {
                "Hold gaze steady"
            } else {
                "Collecting ${pointSamples + 1}/$SAMPLES_PER_POINT"
            }
            canvas.drawText(progress, width / 2f, height - 120f, paint)
        }

        gazePoint?.let { point ->
            paint.color = 0xFFE53935.toInt()
            canvas.drawCircle(point.first * width, point.second * height, 18f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.WHITE
            canvas.drawCircle(point.first * width, point.second * height, 25f, paint)
            paint.style = Paint.Style.FILL
        }
    }

    companion object {
        private const val WARMUP_FRAMES = 3
        private const val SAMPLES_PER_POINT = 8
    }
}
