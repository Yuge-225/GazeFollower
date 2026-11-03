package com.gazefollower.mobile.experiment

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.min

data class EditableRoi(
    val id: String,
    val label: String,
    val roi: NormalizedRoi?,
    val color: Int,
)

/** Displays and edits several independently tracked bounding boxes at once. */
class RoiEditorView(context: Context) : View(context) {
    var onRoiCommitted: ((NormalizedRoi) -> Unit)? = null
    var onMultiRoiCommitted: ((String, NormalizedRoi) -> Unit)? = null
    var onSelectionChanged: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val contentRect = RectF()
    private var videoWidth = 9
    private var videoHeight = 16
    private var items: List<EditableRoi> = emptyList()
    private var selectedId: String? = null
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

    fun setRois(value: List<EditableRoi>, selected: String? = selectedId) {
        items = value
        selectedId = selected?.takeIf { id -> value.any { it.id == id } }
            ?: value.firstOrNull()?.id
        invalidate()
    }

    fun setRoi(value: NormalizedRoi?) {
        setRois(listOf(EditableRoi("legacy", "Box 1", value, 0xFF22C55E.toInt())), "legacy")
    }

    fun currentRoi(): NormalizedRoi? = items.firstOrNull { it.id == selectedId }?.roi

    fun select(id: String) {
        if (items.none { it.id == id }) return
        selectedId = id
        onSelectionChanged?.invoke(id)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        updateContentRect()
        items.forEach { item ->
            val roi = item.roi ?: return@forEach
            val screen = mapToScreen(roi)
            val selected = item.id == selectedId
            paint.style = Paint.Style.FILL
            paint.color = withAlpha(item.color, if (selected) 64 else 35)
            canvas.drawRect(screen, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (selected) 7f else 4f
            paint.color = item.color
            canvas.drawRect(screen, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(screen.right, screen.bottom, if (selected) 20f else 12f, paint)
            paint.textSize = if (selected) 19f else 16f
            paint.color = Color.WHITE
            paint.setShadowLayer(4f, 0f, 1f, Color.BLACK)
            canvas.drawText(item.label, screen.left + 7f, (screen.top - 9f).coerceAtLeast(20f), paint)
            paint.clearShadowLayer()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || items.isEmpty()) return false
        updateContentRect()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interactionChanged = false
                val hit = items.asReversed().firstOrNull { item ->
                    val roi = item.roi ?: return@firstOrNull false
                    val screen = mapToScreen(roi)
                    screen.contains(event.x, event.y) ||
                        (abs(event.x - screen.right) < 70f && abs(event.y - screen.bottom) < 70f)
                }
                if (hit != null && hit.id != selectedId) {
                    selectedId = hit.id
                    onSelectionChanged?.invoke(hit.id)
                }
                val selected = items.firstOrNull { it.id == selectedId } ?: return false
                if (hit == null && selected.roi != null) return true
                val current = selected.roi ?: defaultRoiAt(event.x, event.y)
                if (selected.roi == null) {
                    replaceSelected(current)
                    interactionChanged = true
                }
                val screen = mapToScreen(current)
                resizing = abs(event.x - screen.right) < 70f && abs(event.y - screen.bottom) < 70f
                lastX = event.x
                lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val selected = items.firstOrNull { it.id == selectedId } ?: return true
                val active = selected.roi ?: return true
                val dx = (event.x - lastX) / contentRect.width()
                val dy = (event.y - lastY) / contentRect.height()
                val updated = if (resizing) {
                    active.copy(
                        right = (active.right + dx).coerceIn(active.left + 0.04f, 1f),
                        bottom = (active.bottom + dy).coerceIn(active.top + 0.04f, 1f),
                    )
                } else {
                    val boxWidth = active.right - active.left
                    val boxHeight = active.bottom - active.top
                    val left = (active.left + dx).coerceIn(0f, 1f - boxWidth)
                    val top = (active.top + dy).coerceIn(0f, 1f - boxHeight)
                    NormalizedRoi(left, top, left + boxWidth, top + boxHeight)
                }
                replaceSelected(updated)
                lastX = event.x
                lastY = event.y
                interactionChanged = true
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP && interactionChanged) {
                    val selected = items.firstOrNull { it.id == selectedId }
                    selected?.roi?.let { roi ->
                        onRoiCommitted?.invoke(roi)
                        onMultiRoiCommitted?.invoke(selected.id, roi)
                    }
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

    private fun replaceSelected(roi: NormalizedRoi) {
        items = items.map { if (it.id == selectedId) it.copy(roi = roi) else it }
    }

    private fun defaultRoiAt(x: Float, y: Float): NormalizedRoi {
        val nx = ((x - contentRect.left) / contentRect.width()).coerceIn(0f, 1f)
        val ny = ((y - contentRect.top) / contentRect.height()).coerceIn(0f, 1f)
        return NormalizedRoi(
            (nx - 0.15f).coerceAtLeast(0f),
            (ny - 0.125f).coerceAtLeast(0f),
            (nx + 0.15f).coerceAtMost(1f),
            (ny + 0.125f).coerceAtMost(1f),
        )
    }

    private fun updateContentRect() {
        val scale = min(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val drawWidth = videoWidth * scale
        val drawHeight = videoHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        contentRect.set(left, top, left + drawWidth, top + drawHeight)
    }

    private fun mapToScreen(source: NormalizedRoi) = RectF(
        contentRect.left + source.left * contentRect.width(),
        contentRect.top + source.top * contentRect.height(),
        contentRect.left + source.right * contentRect.width(),
        contentRect.top + source.bottom * contentRect.height(),
    )

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
