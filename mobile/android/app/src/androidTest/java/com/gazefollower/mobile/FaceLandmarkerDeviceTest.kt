package com.gazefollower.mobile

import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gazefollower.mobile.face.FaceLandmarkerProcessor
import com.gazefollower.mobile.face.FaceGeometry
import com.gazefollower.mobile.inference.MnnGazeEstimator
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceLandmarkerDeviceTest {
    @Test
    fun detectsFaceAndEyeRegionsFromBundledReferenceImage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = context.assets.open("face.jpg").use(BitmapFactory::decodeStream)

        FaceLandmarkerProcessor(context).use { detector ->
            val result = detector.detect(bitmap, 0L)
            val geometry = result.geometry
            assertNotNull("MediaPipe did not find the reference face", geometry)
            requireNotNull(geometry)
            assertTrue(geometry.faceRect.width() > geometry.leftEyeRect.width())
            assertTrue(geometry.faceRect.width() > geometry.rightEyeRect.width())
            assertTrue(geometry.leftEyeRect.width() > 0f)
            assertTrue(geometry.rightEyeRect.width() > 0f)
        }

    }

    @Test
    fun runsBaseMnnOnOneReferenceFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = context.assets.open("face.jpg").use(BitmapFactory::decodeStream)
        FaceLandmarkerProcessor(context).use { detector ->
            MnnGazeEstimator(context).use { estimator ->
                val (geometry, result) = detector.useDetection(bitmap, 0L) { detection ->
                    val geometry = requireNotNull(detection.geometry)
                    geometry to estimator.infer(bitmap, geometry)
                }
                Log.i("GazeMnnTest", "geometry=$geometry")
                Log.i(
                    "GazeMnnTest",
                    "count=${result.features.size} raw=${result.rawX},${result.rawY} " +
                        "ms=${result.inferenceMilliseconds}",
                )

                // Pixel rectangles produced by the desktop MediaPipe pipeline
                // for the same 477x476 bundled image. This separates detector
                // differences from MNN/preprocessing differences.
                val desktopGeometry = FaceGeometry(
                    faceRect = RectF(0f, 0f, 1f, 1f),
                    leftEyeRect = RectF(24f / 477f, 92f / 476f, 207f / 477f, 211f / 476f),
                    rightEyeRect = RectF(250f / 477f, 84f / 476f, 433f / 477f, 203f / 476f),
                )
                // The first MediaPipe scope owns/released bitmap; decode a new
                // copy for the fixed-rectangle preprocessing comparison.
                val fixedBitmap = context.assets.open("face.jpg").use(BitmapFactory::decodeStream)
                val fixedResult = estimator.infer(fixedBitmap, desktopGeometry)
                Log.i(
                    "GazeMnnTest",
                    "desktop-rect raw=${fixedResult.rawX},${fixedResult.rawY} " +
                        "ms=${fixedResult.inferenceMilliseconds}",
                )
                assertEquals(0.9555664f, fixedResult.rawX, 0.05f)
                assertEquals(-3.5175781f, fixedResult.rawY, 0.05f)
                fixedBitmap.recycle()
                assertTrue(result.features.size >= 2)
                assertTrue(result.features.all(Float::isFinite))
                assertTrue(result.inferenceMilliseconds >= 0L)
            }
        }
    }
}
