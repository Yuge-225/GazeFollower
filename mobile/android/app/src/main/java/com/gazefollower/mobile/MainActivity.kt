package com.gazefollower.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.gazefollower.mobile.face.FaceFrameResult
import com.gazefollower.mobile.face.FaceLandmarkerProcessor
import com.gazefollower.mobile.face.FaceOverlayView
import com.gazefollower.mobile.inference.GazeInferenceResult
import com.gazefollower.mobile.inference.MnnGazeEstimator
import com.gazefollower.mobile.calibration.GazeCalibrationView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Portrait front-camera baseline for the Galaxy A10. ImageAnalysis intentionally
 * keeps only the latest frame so later MediaPipe/MNN inference cannot build up a
 * stale frame queue.
 */
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlayView: FaceOverlayView
    private lateinit var statusView: TextView
    private lateinit var calibrationView: GazeCalibrationView
    private lateinit var analysisExecutor: ExecutorService
    private var faceProcessor: FaceLandmarkerProcessor? = null
    private var gazeEstimator: MnnGazeEstimator? = null
    private var firstFrameTimestampNs = 0L
    private var analyzedFrames = 0L
    private var latestFaceStatus = ""

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            statusView.text = getString(R.string.camera_permission_required)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()

        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlayView = FaceOverlayView(this)
        calibrationView = GazeCalibrationView(this)
        statusView = TextView(this).apply {
            text = getString(R.string.camera_starting)
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            setBackgroundColor(0xB3101216.toInt())
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(getColor(R.color.background))
            addView(previewView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(overlayView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(calibrationView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ))
            addView(statusView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ))
        }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        statusView.text = getString(R.string.camera_starting)
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get()
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    statusView.text = getString(R.string.camera_unavailable)
                    return@addListener
                }

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(analysisExecutor) { image ->
                            try {
                                val bitmap = image.toUprightBitmap()
                                val detector = faceProcessor ?: FaceLandmarkerProcessor(
                                    applicationContext,
                                ).also { faceProcessor = it }
                                val (frameResult, gazeResult) = detector.useDetection(
                                    bitmap,
                                    image.imageInfo.timestamp / 1_000_000L,
                                ) { result ->
                                    val gaze = result.geometry?.let { geometry ->
                                        val estimator = gazeEstimator ?: MnnGazeEstimator(
                                            applicationContext,
                                        ).also { gazeEstimator = it }
                                        estimator.infer(bitmap, geometry)
                                    }
                                    result to gaze
                                }
                                gazeResult?.let(calibrationView::onGaze)
                                updateFrameStats(
                                    image.imageInfo.timestamp,
                                    frameResult,
                                    gazeResult,
                                )
                            } catch (error: Exception) {
                                statusView.post {
                                    statusView.text = getString(
                                        R.string.face_failed,
                                        error.message ?: error.javaClass.simpleName,
                                    )
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    preview,
                    imageAnalysis,
                )
            } catch (error: Exception) {
                statusView.text = getString(
                    R.string.camera_failed,
                    error.message ?: error.javaClass.simpleName,
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateFrameStats(
        timestampNs: Long,
        faceResult: FaceFrameResult,
        gazeResult: GazeInferenceResult?,
    ) {
        if (firstFrameTimestampNs == 0L) {
            firstFrameTimestampNs = timestampNs
        }
        analyzedFrames += 1
        latestFaceStatus = if (faceResult.geometry == null) {
            getString(R.string.face_missing, faceResult.inferenceMilliseconds)
        } else if (gazeResult != null) {
            "Face ${faceResult.inferenceMilliseconds} ms | MNN ${gazeResult.inferenceMilliseconds} ms | " +
                "raw (${"%.3f".format(gazeResult.rawX)}, ${"%.3f".format(gazeResult.rawY)})"
        } else {
            getString(R.string.face_detected, faceResult.inferenceMilliseconds)
        }
        overlayView.post { overlayView.update(faceResult) }
        if (analyzedFrames % 30L != 0L) return

        val elapsedSeconds = (timestampNs - firstFrameTimestampNs).coerceAtLeast(1L) / 1_000_000_000.0
        val fps = analyzedFrames / elapsedSeconds
        statusView.post {
            statusView.text = getString(
                R.string.camera_running,
                analyzedFrames,
                fps,
                latestFaceStatus,
            )
        }
    }

    private fun androidx.camera.core.ImageProxy.toUprightBitmap(): Bitmap {
        val buffer = planes[0].buffer
        buffer.rewind()
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.copyPixelsFromBuffer(buffer)
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return source
        val rotated = Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        )
        if (rotated !== source) source.recycle()
        return rotated
    }

    override fun onDestroy() {
        super.onDestroy()
        faceProcessor?.close()
        gazeEstimator?.close()
        analysisExecutor.shutdown()
        analysisExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)
    }
}
