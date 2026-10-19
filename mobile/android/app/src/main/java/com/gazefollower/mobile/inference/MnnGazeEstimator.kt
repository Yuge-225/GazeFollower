package com.gazefollower.mobile.inference

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.gazefollower.mobile.face.FaceGeometry
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor

data class GazeInferenceResult(
    val features: FloatArray,
    val inferenceMilliseconds: Long,
) {
    val rawX: Float get() = features[0]
    val rawY: Float get() = features[1]
}

/** CPU-only MNN 3.6.1 wrapper for the desktop base.mnn model. */
class MnnGazeEstimator(context: Context) : Closeable {
    private var nativeHandle: Long

    init {
        val model = context.assets.open("base.mnn").use { it.readBytes() }
        nativeHandle = nativeCreate(model)
        check(nativeHandle != 0L) { "MNN initialization returned a null handle" }
    }

    fun infer(bitmap: Bitmap, geometry: FaceGeometry): GazeInferenceResult {
        check(nativeHandle != 0L) { "MNN estimator is closed" }
        val started = System.nanoTime()
        val face = bitmap.toModelInput(geometry.faceRect, 224, flipHorizontal = false)
        val left = bitmap.toModelInput(geometry.leftEyeRect, 112, flipHorizontal = false)
        val right = bitmap.toModelInput(geometry.rightEyeRect, 112, flipHorizontal = true)
        val rect = geometry.toRectInput(bitmap.width, bitmap.height)
        val features = nativeInfer(nativeHandle, face, left, right, rect)
        require(features.size >= 2) { "MNN output_0 contains fewer than two values" }
        require(features.all(Float::isFinite)) { "MNN output_0 contains a non-finite value" }
        return GazeInferenceResult(
            features = features,
            inferenceMilliseconds = (System.nanoTime() - started) / 1_000_000L,
        )
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeClose(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(modelBytes: ByteArray): Long
    private external fun nativeInfer(
        handle: Long,
        face: FloatArray,
        left: FloatArray,
        right: FloatArray,
        rect: FloatArray,
    ): FloatArray
    private external fun nativeClose(handle: Long)

    companion object {
        init {
            System.loadLibrary("gazefollower_mnn")
        }
    }
}

private fun Bitmap.toModelInput(rect: RectF, size: Int, flipHorizontal: Boolean): FloatArray {
    val left = floor(rect.left * width).toInt().coerceIn(0, width - 1)
    val top = floor(rect.top * height).toInt().coerceIn(0, height - 1)
    val right = ceil(rect.right * width).toInt().coerceIn(left + 1, width)
    val bottom = ceil(rect.bottom * height).toInt().coerceIn(top + 1, height)
    val crop = Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    val scaled = Bitmap.createScaledBitmap(crop, size, size, true)

    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    // Do not recycle crop/scaled here. On Android 9 a subset bitmap may share
    // its native pixel storage with the source frame, so recycling it can make
    // the next crop abort inside Bitmap.createBitmap().
    val output = FloatArray(size * size * 3)
    var outputIndex = 0
    for (y in 0 until size) {
        for (x in 0 until size) {
            val sourceX = if (flipHorizontal) size - 1 - x else x
            val color = pixels[y * size + sourceX]
            // Desktop frames come from OpenCV, so the model expects BGR channel order.
            output[outputIndex++] = (color and 0xff) / 255f
            output[outputIndex++] = ((color shr 8) and 0xff) / 255f
            output[outputIndex++] = ((color shr 16) and 0xff) / 255f
        }
    }
    return output
}

private fun FaceGeometry.toRectInput(imageWidth: Int, imageHeight: Int): FloatArray {
    fun append(rect: RectF, output: FloatArray, offset: Int) {
        output[offset] = rect.width()
        output[offset + 1] = rect.height()
        output[offset + 2] = rect.left
        output[offset + 3] = rect.top
    }
    return FloatArray(12).also {
        // Geometry is already normalized by image width/height. Arguments remain
        // explicit to document and guard the coordinate system used here.
        require(imageWidth > 0 && imageHeight > 0)
        append(faceRect, it, 0)
        append(leftEyeRect, it, 4)
        append(rightEyeRect, it, 8)
    }
}
