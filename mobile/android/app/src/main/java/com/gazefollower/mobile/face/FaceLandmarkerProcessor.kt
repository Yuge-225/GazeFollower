package com.gazefollower.mobile.face

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.Closeable

class FaceLandmarkerProcessor(context: Context) : Closeable {
    private val landmarker: FaceLandmarker

    init {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("face_landmarker.task")
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.VIDEO)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.1f)
            .setMinFacePresenceConfidence(0.1f)
            .setMinTrackingConfidence(0.1f)
            .setOutputFaceBlendshapes(false)
            .setOutputFacialTransformationMatrixes(false)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap, timestampMilliseconds: Long): FaceFrameResult {
        return useDetection(bitmap, timestampMilliseconds) { it }
    }

    /**
     * Runs [consume] before the MediaPipe image releases its backing bitmap.
     * Android 9 may transfer bitmap ownership to MPImage, so pixel consumers
     * such as MNN must finish inside this scope.
     */
    fun <T> useDetection(
        bitmap: Bitmap,
        timestampMilliseconds: Long,
        consume: (FaceFrameResult) -> T,
    ): T {
        val started = SystemClock.elapsedRealtimeNanos()
        val mpImage = BitmapImageBuilder(bitmap).build()
        return try {
            val result = landmarker.detectForVideo(mpImage, timestampMilliseconds)
            val geometry = result.faceLandmarks().firstOrNull()?.let {
                FaceGeometryCalculator.calculate(it)
            }
            val elapsed = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L
            consume(FaceFrameResult(geometry, bitmap.width, bitmap.height, elapsed))
        } finally {
            mpImage.close()
        }
    }

    override fun close() {
        landmarker.close()
    }
}
