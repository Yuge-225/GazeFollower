package com.gazefollower.mobile.experiment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

class RoiEditorView(context: Context) : View(context) {
    var onRoiCommitted: ((NormalizedRoi) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contentRect = RectF()
    private val screenRoi = RectF()
    private var videoWidth = 9
    private var videoHeight = 16
    private var roi: NormalizedRoi? = null
    private var lastX = 0f
    private var lastY = 0f
    private var resizing = false
    private var interactionChanged = false

    fun setVideoSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            videoWidth = width
            videoHeight = height
            invalidate()
        }
    }

    fun setRoi(value: NormalizedRoi?) {
        roi = value
        invalidate()
    }

    fun currentRoi(): NormalizedRoi? = roi

    override fun onDraw(canvas: Canvas) {
        updateContentRect()
        val current = roi ?: return
        mapToScreen(current, screenRoi)
        paint.style = Paint.Style.FILL
        paint.color = 0x3322C55E
        canvas.drawRect(screenRoi, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = 0xFF22C55E.toInt()
        canvas.drawRect(screenRoi, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(screenRoi.right, screenRoi.bottom, 18f, paint)
        paint.textSize = 17f
        paint.color = Color.WHITE
        canvas.drawText("Target ROI", screenRoi.left + 8f, screenRoi.top - 10f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        updateContentRect()
        val hadRoi = roi != null
        val current = roi ?: NormalizedRoi(0.35f, 0.2f, 0.65f, 0.45f).also { roi = it }
        mapToScreen(current, screenRoi)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interactionChanged = false
                lastX = event.x
                lastY = event.y
                resizing = kotlin.math.abs(event.x - screenRoi.right) < 70f &&
                    kotlin.math.abs(event.y - screenRoi.bottom) < 70f
                if (!hadRoi || (!screenRoi.contains(event.x, event.y) && !resizing)) {
                    val nx = ((event.x - contentRect.left) / contentRect.width()).coerceIn(0f, 1f)
                    val ny = ((event.y - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
                    roi = NormalizedRoi(
                        (nx - 0.15f).coerceAtLeast(0f),
                        (ny - 0.125f).coerceAtLeast(0f),
                        (nx + 0.15f).coerceAtMost(1f),
                        (ny + 0.125f).coerceAtMost(1f),
                    )
                    resizing = false
                    interactionChanged = true
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val active = roi ?: return true
                val dx = (event.x - lastX) / contentRect.width()
                val dy = (event.y - lastY) / contentRect.height()
                roi = if (resizing) {
                    active.copy(
                        right = (active.right + dx).coerceIn(active.left + 0.04f, 1f),
                        bottom = (active.bottom + dy).coerceIn(active.top + 0.04f, 1f),
                    )
                } else {
                    val width = active.right - active.left
                    val height = active.bottom - active.top
                    val left = (active.left + dx).coerceIn(0f, 1f - width)
                    val top = (active.top + dy).coerceIn(0f, 1f - height)
                    NormalizedRoi(left, top, left + width, top + height)
                }
                lastX = event.x
                lastY = event.y
                interactionChanged = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && interactionChanged) {
                    roi?.let { onRoiCommitted?.invoke(it) }
                }
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun updateContentRect() {
        val scale = min(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val drawWidth = videoWidth * scale
        val drawHeight = videoHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        contentRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    private fun mapToScreen(source: NormalizedRoi, target: RectF) {
        target.set(
            contentRect.left + source.left * contentRect.width(),
            contentRect.top + source.top * contentRect.height(),
            contentRect.left + source.right * contentRect.width(),
            contentRect.top + source.bottom * contentRect.height(),
        )
    }
}
