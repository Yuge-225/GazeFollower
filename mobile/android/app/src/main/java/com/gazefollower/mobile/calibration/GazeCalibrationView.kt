package com.gazefollower.mobile.calibration

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.gazefollower.mobile.inference.GazeInferenceResult
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class GazeCalibrationView(context: Context) : View(context) {
    enum class Stage { PREVIEW, CALIBRATION, CALIBRATION_RESULT, LIVE_TEST, HEATMAP_RESULT }

    var onStageChanged: ((Stage) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val primaryButton = RectF()
    private val secondaryButton = RectF()
    private val bodyRect = RectF()
    private val targets = listOf(
        0.5f to 0.5f,
        0.1f to 0.12f, 0.5f to 0.12f, 0.9f to 0.12f,
        0.1f to 0.5f, 0.9f to 0.5f,
        0.1f to 0.88f, 0.5f to 0.88f, 0.9f to 0.88f,
    )
    private val samples = mutableListOf<FloatArray>()
    private val labels = mutableListOf<FloatArray>()
    private val heatmap = HeatmapAccumulator()
    private val bodyBitmap: Bitmap? = context.assets.open("human_body_front.png").use {
        BitmapFactory.decodeStream(it)
    }
    private var heatBitmap: Bitmap? = null
    private var heatmapDirty = true
    private var model: RidgeCalibration? = null
    private var stage = Stage.PREVIEW
    private var pointIndex = 0
    private var warmupFrames = 0
    private var pointSamples = 0
    private var pointChangedAt = 0L
    private var previousTarget = targets.first()
    private var gazePoint: Pair<Float, Float>? = null
    private var gazeOnBody = false
    private var currentBodyRegion = OUTSIDE_BODY
    private val bodyRegionSamples = mutableMapOf<String, Int>()
    private var contentAlpha = 1f

    @Synchronized
    fun onGaze(result: GazeInferenceResult) {
        when (stage) {
            Stage.CALIBRATION -> collect(result.features)
            Stage.LIVE_TEST -> {
                val prediction = model?.predict(result.features) ?: return
                val insideScreen = prediction[0] >= 0f && prediction[0] < 1f &&
                    prediction[1] >= 0f && prediction[1] < 1f
                val point = prediction[0].coerceIn(0f, 1f) to prediction[1].coerceIn(0f, 1f)
                gazePoint = point
                currentBodyRegion = if (insideScreen) {
                    bodyRegion(point.first, point.second)
                } else {
                    OUTSIDE_SCREEN
                }
                gazeOnBody = currentBodyRegion != OUTSIDE_BODY
                    && currentBodyRegion != OUTSIDE_SCREEN
                if (gazeOnBody) {
                    bodyRegionSamples[currentBodyRegion] =
                        (bodyRegionSamples[currentBodyRegion] ?: 0) + 1
                }
                // Heatmap coverage is screen-wide. The body silhouette is only
                // used to classify the current focus region for feedback.
                if (insideScreen) {
                    heatmap.add(point.first, point.second)
                    heatmapDirty = true
                }
            }
            else -> Unit
        }
        postInvalidateOnAnimation()
    }

    @Synchronized
    private fun collect(features: FloatArray) {
        // Calibration labels describe the target's final normalized position.
        // Do not collect while the target is still moving toward that position.
        if (SystemClock.uptimeMillis() - pointChangedAt < TARGET_MOVE_MS) return
        if (warmupFrames < WARMUP_FRAMES) {
            warmupFrames += 1
            return
        }
        val target = targets[pointIndex]
        samples += features.clone()
        labels += floatArrayOf(target.first, target.second)
        pointSamples += 1
        if (pointSamples < SAMPLES_PER_POINT) return

        previousTarget = target
        pointIndex += 1
        pointChangedAt = SystemClock.uptimeMillis()
        warmupFrames = 0
        pointSamples = 0
        if (pointIndex < targets.size) return
        try {
            model = RidgeCalibration().also { it.fit(samples, labels) }
            // Stop the analyzer thread from collecting a tenth point while the
            // main thread is waiting to render the result transition.
            stage = Stage.CALIBRATION_RESULT
            post { transitionTo(Stage.CALIBRATION_RESULT) }
        } catch (_: Exception) {
            post { startCalibration() }
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
        previousTarget = targets.first()
        pointChangedAt = SystemClock.uptimeMillis()
        transitionTo(Stage.CALIBRATION)
    }

    @Synchronized
    private fun startLiveTest() {
        heatmap.clear()
        heatmapDirty = true
        gazePoint = null
        gazeOnBody = false
        currentBodyRegion = OUTSIDE_BODY
        bodyRegionSamples.clear()
        transitionTo(Stage.LIVE_TEST)
    }

    @Synchronized
    private fun transitionTo(next: Stage) {
        stage = next
        contentAlpha = 0f
        onStageChanged?.invoke(next)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                contentAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        when (stage) {
            Stage.PREVIEW -> if (primaryButton.contains(event.x, event.y)) startCalibration()
            Stage.CALIBRATION -> Unit
            Stage.CALIBRATION_RESULT -> when {
                primaryButton.contains(event.x, event.y) -> startLiveTest()
                secondaryButton.contains(event.x, event.y) -> startCalibration()
            }
            Stage.LIVE_TEST -> when {
                primaryButton.contains(event.x, event.y) -> transitionTo(Stage.HEATMAP_RESULT)
                secondaryButton.contains(event.x, event.y) -> {
                    heatmap.clear()
                    bodyRegionSamples.clear()
                    currentBodyRegion = OUTSIDE_BODY
                    heatmapDirty = true
                    invalidate()
                }
            }
            Stage.HEATMAP_RESULT -> when {
                primaryButton.contains(event.x, event.y) -> startLiveTest()
                secondaryButton.contains(event.x, event.y) -> transitionTo(Stage.PREVIEW)
            }
        }
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @Synchronized
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        when (stage) {
            Stage.PREVIEW -> drawPreviewControls(canvas)
            Stage.CALIBRATION -> drawCalibration(canvas)
            Stage.CALIBRATION_RESULT -> drawCalibrationResult(canvas)
            Stage.LIVE_TEST -> drawBodyTest(canvas, false)
            Stage.HEATMAP_RESULT -> drawBodyTest(canvas, true)
        }
    }

    private fun drawPreviewControls(canvas: Canvas) {
        primaryButton.set(width * 0.22f, 24f, width * 0.78f, 104f)
        drawButton(canvas, primaryButton, "Start 9-point calibration", true)
    }

    private fun drawCalibration(canvas: Canvas) {
        drawStageBackground(canvas)
        drawHeader(canvas, "CALIBRATION", "Look steadily at the moving target")

        val target = targets[pointIndex.coerceAtMost(targets.lastIndex)]
        val elapsed = SystemClock.uptimeMillis() - pointChangedAt
        val moveProgress = (elapsed / TARGET_MOVE_MS.toFloat()).coerceIn(0f, 1f)
        val eased = 1f - (1f - moveProgress).pow(3)
        val normalizedX = previousTarget.first + (target.first - previousTarget.first) * eased
        val normalizedY = previousTarget.second + (target.second - previousTarget.second) * eased
        val x = normalizedX * width
        // Keep the rendered target in the same full-view normalized coordinate
        // system used by the labels and the GitHub baseline implementation.
        val y = normalizedY * height
        val pulse = ((sin(SystemClock.uptimeMillis() / 180.0 * PI) + 1.0) * 0.5).toFloat()
        val radius = 28f + pulse * 7f

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = 0x5538BDF8
        canvas.drawCircle(x, y, radius + 18f, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, radius, paint)
        paint.color = 0xFFEF4444.toInt()
        canvas.drawCircle(x, y, 10f + pulse * 2f, paint)

        val totalFrames = WARMUP_FRAMES + SAMPLES_PER_POINT
        val progress = (warmupFrames + pointSamples).toFloat() / totalFrames
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF38BDF8.toInt()
        canvas.drawArc(RectF(x - radius - 10f, y - radius - 10f, x + radius + 10f, y + radius + 10f), -90f, progress * 360f, false, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.style = Paint.Style.FILL

        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 27f
        paint.color = Color.WHITE
        canvas.drawText("Point ${pointIndex + 1} of ${targets.size}", width / 2f, height - 100f, paint)
        paint.textSize = 20f
        paint.color = 0xFF94A3B8.toInt()
        canvas.drawText(if (warmupFrames < WARMUP_FRAMES) "Hold your gaze" else "Keep looking at the center", width / 2f, height - 66f, paint)
        postInvalidateOnAnimation()
    }

    private fun drawCalibrationResult(canvas: Canvas) {
        drawStageBackground(canvas)
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0xFF22C55E.toInt()
        paint.textSize = 76f
        canvas.drawText("✓", width / 2f, height * 0.31f, paint)
        paint.color = Color.WHITE
        paint.textSize = 36f
        canvas.drawText("Calibration complete", width / 2f, height * 0.39f, paint)
        paint.color = 0xFF94A3B8.toInt()
        paint.textSize = 22f
        canvas.drawText("72 samples · 9 screen positions", width / 2f, height * 0.44f, paint)

        primaryButton.set(width * 0.16f, height * 0.57f, width * 0.84f, height * 0.57f + 88f)
        secondaryButton.set(width * 0.16f, height * 0.57f + 108f, width * 0.84f, height * 0.57f + 196f)
        drawButton(canvas, primaryButton, "Start body Live Test", true)
        drawButton(canvas, secondaryButton, "Recalibrate", false)
    }

    private fun drawBodyTest(canvas: Canvas, frozen: Boolean) {
        drawStageBackground(canvas)
        updateBodyRect()
        bodyBitmap?.let { bitmap ->
            paint.alpha = (255 * contentAlpha).toInt()
            canvas.drawBitmap(bitmap, null, bodyRect, paint)
            paint.alpha = 255
        }
        if (heatmapDirty || heatBitmap == null) rebuildHeatBitmap()
        heatBitmap?.let { canvas.drawBitmap(it, null, RectF(0f, 0f, width.toFloat(), height.toFloat()), paint) }

        drawHeader(
            canvas,
            if (frozen) "HEATMAP RESULT" else "BODY LIVE TEST",
            if (frozen) "${heatmap.sampleCount} valid screen samples" else "Full-screen gaze density",
        )
        drawBodyInfoPanel(canvas, frozen)

        if (!frozen) gazePoint?.let { point ->
            val x = point.first * width
            val y = point.second * height
            paint.style = Paint.Style.FILL
            paint.color = if (gazeOnBody) 0xFFF8FAFC.toInt() else 0xFF64748B.toInt()
            canvas.drawCircle(x, y, 9f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            canvas.drawCircle(x, y, 17f, paint)
            paint.style = Paint.Style.FILL
        }

        primaryButton.set(width * 0.12f, height - 112f, width * 0.58f, height - 36f)
        secondaryButton.set(width * 0.62f, height - 112f, width * 0.88f, height - 36f)
        drawButton(canvas, primaryButton, if (frozen) "Run again" else "Finish", true)
        drawButton(canvas, secondaryButton, if (frozen) "Preview" else "Clear", false)
    }

    private fun drawStageBackground(canvas: Canvas) {
        canvas.drawColor(Color.argb((255 * contentAlpha).toInt(), 10, 17, 28))
    }

    private fun drawHeader(canvas: Canvas, title: String, subtitle: String) {
        paint.textAlign = Paint.Align.CENTER
        paint.color = Color.argb((255 * contentAlpha).toInt(), 248, 250, 252)
        paint.textSize = 25f
        canvas.drawText(title, width / 2f, 52f, paint)
        paint.color = Color.argb((255 * contentAlpha).toInt(), 148, 163, 184)
        paint.textSize = 18f
        canvas.drawText(subtitle, width / 2f, 82f, paint)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String, primary: Boolean) {
        paint.style = Paint.Style.FILL
        paint.color = if (primary) 0xFF0284C7.toInt() else 0xCC1E293B.toInt()
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        if (!primary) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = 0xFF475569.toInt()
            canvas.drawRoundRect(rect, 22f, 22f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = if (rect.width() < width * 0.35f) 19f else 23f
        paint.color = Color.WHITE
        canvas.drawText(label, rect.centerX(), rect.centerY() + 8f, paint)
    }

    private fun updateBodyRect() {
        val bitmap = bodyBitmap ?: return
        val availableTop = 105f
        val availableBottom = height - 135f
        val maxWidth = width * 0.86f
        val maxHeight = availableBottom - availableTop
        val scale = min(maxWidth / bitmap.width, maxHeight / bitmap.height)
        val drawWidth = bitmap.width * scale
        val drawHeight = bitmap.height * scale
        val left = (width - drawWidth) / 2f
        val top = availableTop + (maxHeight - drawHeight) / 2f
        bodyRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    private fun bodyRegion(normalizedX: Float, normalizedY: Float): String {
        val bitmap = bodyBitmap ?: return OUTSIDE_BODY
        if (width == 0 || height == 0 || bodyRect.isEmpty) return OUTSIDE_BODY
        val x = normalizedX * width
        val y = normalizedY * height
        if (!bodyRect.contains(x, y)) return OUTSIDE_BODY
        val bitmapX = (((x - bodyRect.left) / bodyRect.width()) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val bitmapY = (((y - bodyRect.top) / bodyRect.height()) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        if (Color.alpha(bitmap.getPixel(bitmapX, bitmapY)) <= 24) return OUTSIDE_BODY

        val relativeX = (x - bodyRect.left) / bodyRect.width()
        val relativeY = (y - bodyRect.top) / bodyRect.height()
        return when {
            relativeY < 0.15f -> "Head"
            relativeY < 0.57f && (relativeX < 0.29f || relativeX > 0.71f) -> when {
                relativeY < 0.31f -> "Upper arms"
                relativeY < 0.48f -> "Forearms"
                else -> "Hands"
            }
            relativeY < 0.34f -> "Chest"
            relativeY < 0.47f -> "Abdomen"
            relativeY < 0.58f -> "Pelvis"
            relativeY < 0.73f -> "Thighs"
            relativeY < 0.80f -> "Knees"
            relativeY < 0.93f -> "Lower legs"
            else -> "Feet"
        }
    }

    private fun rebuildHeatBitmap() {
        val values = heatmap.snapshot()
        val positive = values.filter { it > 0f }.sorted()
        val scale = if (positive.isEmpty()) 1f else positive[(positive.size * 0.98f).toInt().coerceAtMost(positive.lastIndex)]
        val pixels = IntArray(heatmap.width * heatmap.height)
        for (y in 0 until heatmap.height) {
            for (x in 0 until heatmap.width) {
                val value = (values[y * heatmap.width + x] / max(scale, 0.0001f)).coerceIn(0f, 1f)
                if (value <= 0.01f) continue
                pixels[y * heatmap.width + x] = heatColor(value)
            }
        }
        val bitmap = heatBitmap ?: Bitmap.createBitmap(heatmap.width, heatmap.height, Bitmap.Config.ARGB_8888).also { heatBitmap = it }
        bitmap.setPixels(pixels, 0, heatmap.width, 0, 0, heatmap.width, heatmap.height)
        heatmapDirty = false
    }

    private fun heatColor(value: Float): Int {
        val stops = intArrayOf(0xFF2563EB.toInt(), 0xFF06B6D4.toInt(), 0xFF22C55E.toInt(), 0xFFFACC15.toInt(), 0xFFEF4444.toInt())
        val scaled = value * (stops.size - 1)
        val index = scaled.toInt().coerceAtMost(stops.size - 2)
        val fraction = scaled - index
        fun mix(first: Int, second: Int): Int = (first + (second - first) * fraction).toInt()
        val first = stops[index]
        val second = stops[index + 1]
        val alpha = (55 + 180 * value.pow(0.55f)).toInt().coerceAtMost(220)
        return Color.argb(alpha, mix(Color.red(first), Color.red(second)), mix(Color.green(first), Color.green(second)), mix(Color.blue(first), Color.blue(second)))
    }

    private fun drawBodyInfoPanel(canvas: Canvas, frozen: Boolean) {
        val panel = RectF(width - 294f, 102f, width - 16f, 232f)
        paint.style = Paint.Style.FILL
        paint.color = 0xD90F172A.toInt()
        canvas.drawRoundRect(panel, 18f, 18f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = 0xFF334155.toInt()
        canvas.drawRoundRect(panel, 18f, 18f, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.RIGHT

        val region = if (frozen) dominantBodyRegion() else currentBodyRegion
        val regionSamples = bodyRegionSamples[region] ?: 0
        paint.textSize = 14f
        paint.color = 0xFF94A3B8.toInt()
        canvas.drawText(if (frozen) "DOMINANT BODY REGION" else "CURRENT BODY REGION", panel.right - 14f, panel.top + 24f, paint)
        paint.textSize = 23f
        paint.color = if (region == OUTSIDE_BODY || region == OUTSIDE_SCREEN) {
            0xFFF87171.toInt()
        } else {
            0xFF86EFAC.toInt()
        }
        canvas.drawText(region, panel.right - 14f, panel.top + 52f, paint)

        paint.textSize = 14f
        paint.color = 0xFFCBD5E1.toInt()
        val point = gazePoint
        val coordinates = if (point == null) {
            "x --   y --"
        } else {
            "x ${(point.first * width).toInt()}   y ${(point.second * height).toInt()} px"
        }
        canvas.drawText(coordinates, panel.right - 14f, panel.top + 78f, paint)
        canvas.drawText("Region $regionSamples · Screen ${heatmap.sampleCount}", panel.right - 14f, panel.top + 103f, paint)
    }

    private fun dominantBodyRegion(): String {
        return bodyRegionSamples.maxByOrNull { it.value }?.key ?: OUTSIDE_BODY
    }

    override fun onDetachedFromWindow() {
        heatBitmap?.recycle()
        bodyBitmap?.recycle()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val WARMUP_FRAMES = 3
        private const val SAMPLES_PER_POINT = 8
        private const val TARGET_MOVE_MS = 360L
        private const val OUTSIDE_BODY = "Outside body"
        private const val OUTSIDE_SCREEN = "Outside screen"
    }
}
