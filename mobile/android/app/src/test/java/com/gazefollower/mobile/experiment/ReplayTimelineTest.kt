package com.gazefollower.mobile.experiment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReplayTimelineTest {
    @Test
    fun manualReplayAppendsTwoCompletePassesAndStopsAtTheSwipe() {
        val replay = ReplayTimeline(10_000, 2, 3_000, 23_000, manualScroll = true, endedBySwipe = true)
        assertEquals(3, replay.passCount)
        assertEquals(23_000L, replay.durationMs)
        assertEquals(ReplayTimeline.Position(1, 0, 9_999, 10_000), replay.positionAt(9_999))
        assertEquals(ReplayTimeline.Position(2, 10_000, 0, 10_000), replay.positionAt(10_000))
        assertEquals(ReplayTimeline.Position(3, 20_000, 2_500, 3_000), replay.positionAt(22_500))
        assertEquals(ReplayTimeline.Position(3, 20_000, 3_000, 3_000), replay.positionAt(30_000))
    }

    @Test
    fun swipeAtTheLoopBoundaryDoesNotCreateAnEmptyPass() {
        val replay = ReplayTimeline(10_000, 1, 0, 10_000, manualScroll = true, endedBySwipe = true)
        assertEquals(1, replay.passCount)
        assertEquals(ReplayTimeline.Position(1, 0, 10_000, 10_000), replay.positionAt(10_000))
    }

    @Test
    fun legacySwipeReplayPreservesTheUnscoredTail() {
        val replay = ReplayTimeline(10_000, 0, 3_000, 3_000, manualScroll = false, endedBySwipe = true)
        assertEquals(10_000L, replay.durationMs)
        assertFalse(replay.hasGazeAt(replay.positionAt(3_001)))
    }
}
