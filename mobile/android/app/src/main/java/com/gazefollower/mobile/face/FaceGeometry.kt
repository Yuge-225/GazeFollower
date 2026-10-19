package com.gazefollower.mobile.face

import android.graphics.RectF
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FaceGeometry(
    val faceRect: RectF,
    val leftEyeRect: RectF,
    val rightEyeRect: RectF,
)

data class FaceFrameResult(
    val geometry: FaceGeometry?,
    val imageWidth: Int,
    val imageHeight: Int,
    val inferenceMilliseconds: Long,
)

/** Reproduces the face/eye crop definitions used by the desktop implementation. */
object FaceGeometryCalculator {
    private const val LEFT_OUTER = 33
    private const val LEFT_INNER = 133
    private const val RIGHT_INNER = 362
    private const val RIGHT_OUTER = 263

    fun calculate(landmarks: List<NormalizedLandmark>): FaceGeometry? {
        if (landmarks.size <= RIGHT_INNER) return null

        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        landmarks.forEach { point ->
            minX = min(minX, point.x())
            minY = min(minY, point.y())
            maxX = max(maxX, point.x())
            maxY = max(maxY, point.y())
        }

        val faceWidth = maxX - minX
        val faceHeight = maxY - minY
        val delta = (faceWidth - faceHeight) / 4f
        val faceRect = RectF(
            (minX + delta).coerceIn(0f, 1f),
            (minY - delta).coerceIn(0f, 1f),
            (maxX - delta).coerceIn(0f, 1f),
            (maxY + delta).coerceIn(0f, 1f),
        )

        val interEyeScale = abs(landmarks[RIGHT_INNER].x() - landmarks[LEFT_INNER].x()) / 100f
        val padding = 20f * interEyeScale
        val leftEyeRect = eyeRect(
            landmarks[LEFT_OUTER],
            landmarks[LEFT_INNER],
            padding,
        )
        val rightEyeRect = eyeRect(
            landmarks[RIGHT_INNER],
            landmarks[RIGHT_OUTER],
            padding,
        )

        if (faceRect.width() <= 0f || faceRect.height() <= 0f ||
            leftEyeRect.width() <= 0f || leftEyeRect.height() <= 0f ||
            rightEyeRect.width() <= 0f || rightEyeRect.height() <= 0f
        ) return null

        return FaceGeometry(faceRect, leftEyeRect, rightEyeRect)
    }

    private fun eyeRect(
        first: NormalizedLandmark,
        second: NormalizedLandmark,
        padding: Float,
    ): RectF {
        val left = min(first.x(), second.x()) - padding
        val right = max(first.x(), second.x()) + padding
        val height = (right - left) * 0.75f
        val centerY = (first.y() + second.y()) / 2f
        return RectF(
            left.coerceIn(0f, 1f),
            (centerY - height * 0.6f).coerceIn(0f, 1f),
            right.coerceIn(0f, 1f),
            (centerY + height * 0.4f).coerceIn(0f, 1f),
        )
    }
}
