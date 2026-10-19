package com.gazefollower.mobile.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val facePaint = boxPaint(Color.GREEN)
    private val leftEyePaint = boxPaint(Color.BLUE)
    private val rightEyePaint = boxPaint(Color.RED)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        style = Paint.Style.FILL
    }
    private var result: FaceFrameResult? = null

    fun update(newResult: FaceFrameResult) {
        result = newResult
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = result ?: return
        val geometry = frame.geometry ?: return
        val scale = max(width / frame.imageWidth.toFloat(), height / frame.imageHeight.toFloat())
        val displayedWidth = frame.imageWidth * scale
        val displayedHeight = frame.imageHeight * scale
        val offsetX = (width - displayedWidth) / 2f
        val offsetY = (height - displayedHeight) / 2f

        fun map(source: RectF): RectF {
            // CameraX mirrors the front-camera preview; analysis frames are not mirrored.
            val mirroredLeft = 1f - source.right
            val mirroredRight = 1f - source.left
            return RectF(
                offsetX + mirroredLeft * displayedWidth,
                offsetY + source.top * displayedHeight,
                offsetX + mirroredRight * displayedWidth,
                offsetY + source.bottom * displayedHeight,
            )
        }

        val face = map(geometry.faceRect)
        val leftEye = map(geometry.leftEyeRect)
        val rightEye = map(geometry.rightEyeRect)
        canvas.drawRect(face, facePaint)
        canvas.drawRect(leftEye, leftEyePaint)
        canvas.drawRect(rightEye, rightEyePaint)
        canvas.drawText("L", leftEye.left, leftEye.top - 6f, labelPaint)
        canvas.drawText("R", rightEye.left, rightEye.top - 6f, labelPaint)
    }

    private fun boxPaint(colorValue: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorValue
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }
}
