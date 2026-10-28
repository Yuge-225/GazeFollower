package com.gazefollower.mobile.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExperimentVideoTest {
    private fun video(vararg keyframes: RoiKeyframe) = ExperimentVideo(
        id = "video",
        displayName = "video.mp4",
        localPath = "/tmp/video.mp4",
        durationMs = 1_000,
        width = 1080,
        height = 1920,
        target = BodyTarget.HEAD,
        keyframes = keyframes.toMutableList(),
    )

    @Test
    fun authoringUsesTheFiveCurrentBodyRegionsInOrder() {
        assertEquals(
            listOf(
                BodyTarget.HEAD,
                BodyTarget.CHEST,
                BodyTarget.ABDOMEN,
                BodyTarget.LEG,
                BodyTarget.FOOT,
            ),
            BodyTarget.authorChoices,
        )
        assertEquals(false, BodyTarget.LEFT_ARM.availableForAuthoring)
        assertEquals(false, BodyTarget.RIGHT_ARM.availableForAuthoring)
        assertEquals(false, BodyTarget.LEGS.availableForAuthoring)
    }

    @Test
    fun holdsLastBoxUntilTheNextEdit() {
        val subject = video(
            RoiKeyframe(0, true, NormalizedRoi(0.1f, 0.2f, 0.3f, 0.4f)),
            RoiKeyframe(1_000, true, NormalizedRoi(0.3f, 0.4f, 0.5f, 0.6f)),
        )

        val roi = subject.roiAt(500)!!

        assertEquals(0.1f, roi.left, 0.0001f)
        assertEquals(0.2f, roi.top, 0.0001f)
        assertEquals(0.3f, roi.right, 0.0001f)
        assertEquals(0.4f, roi.bottom, 0.0001f)
    }

    @Test
    fun finalFrameEditDoesNotChangeTheFirstFrame() {
        val first = NormalizedRoi(0.1f, 0.2f, 0.3f, 0.4f)
        val last = NormalizedRoi(0.6f, 0.5f, 0.9f, 0.8f)
        val subject = video(
            RoiKeyframe(0, true, first),
            RoiKeyframe(1_000, true, last),
        )

        assertEquals(first, subject.roiAt(0))
        assertEquals(first, subject.roiAt(999))
        assertEquals(last, subject.roiAt(1_000))
    }

    @Test
    fun invisibleBoundaryAppliesFromItsTimestamp() {
        val subject = video(
            RoiKeyframe(0, true, NormalizedRoi(0.1f, 0.2f, 0.3f, 0.4f)),
            RoiKeyframe(1_000, false, null),
        )

        assertEquals(0.1f, subject.roiAt(750)!!.left, 0.0001f)
        assertNull(subject.roiAt(1_000))
    }

    @Test
    fun expandsRoiWithinNormalizedBounds() {
        val expanded = NormalizedRoi(0f, 0f, 0.2f, 0.2f).expanded(0.2f)

        assertEquals(0f, expanded.left, 0f)
        assertEquals(0f, expanded.top, 0f)
        assertEquals(0.22f, expanded.right, 0.0001f)
        assertEquals(0.22f, expanded.bottom, 0.0001f)
    }
}
