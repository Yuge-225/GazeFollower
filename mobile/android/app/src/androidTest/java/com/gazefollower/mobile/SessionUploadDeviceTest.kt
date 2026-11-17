package com.gazefollower.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gazefollower.mobile.experiment.SessionUpload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SessionUploadDeviceTest {
    @Test
    fun uploadsSyntheticSessionOnce() {
        val arguments = InstrumentationRegistry.getArguments()
        val url = arguments.getString("dashboardUrl")
        val password = arguments.getString("dashboardPassword")
        assumeTrue("Requires a disposable dashboard and USB reverse tunnel", url != null && password != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "upload-test-${UUID.randomUUID()}").apply { mkdir() }
        try {
            // The browser test separately uses an actual synthetic video and checks decoding.
            val video = directory.resolve("transport-fixture.mp4").apply { writeText("Synthetic transport fixture") }
            directory.resolve("captured_samples.json").writeText("[]")
            val sessionId = UUID.randomUUID().toString()
            val source = """
                {
                  "sessionId": "$sessionId", "participantCode": "QA001",
                  "experimentId": "synthetic-device-check", "experimentVersion": 1,
                  "experimentName": "Synthetic Android upload", "presentationMode": "READY",
                  "completedAtMs": ${System.currentTimeMillis()},
                  "experimentSnapshot": {"videos": [{"id": "stimulus", "displayName": "Transport fixture"}]},
                  "trials": [{"videoId": "stimulus", "videoName": "Transport fixture",
                    "videoDurationMs": 1000, "presentedDurationMs": 1000, "validSamples": 0}]
                }
            """.trimIndent()
            fun summary() = JSONObject(source).apply {
                getJSONObject("experimentSnapshot").getJSONArray("videos").getJSONObject(0)
                    .put("localPath", video.absolutePath)
            }
            assertEquals("Uploaded", SessionUpload.upload(URI(url!!), password!!, directory, summary()))
            video.delete()
            assertEquals("Already Uploaded", SessionUpload.upload(URI(url), password, directory, summary()))
        } finally {
            directory.deleteRecursively()
        }
    }
}
