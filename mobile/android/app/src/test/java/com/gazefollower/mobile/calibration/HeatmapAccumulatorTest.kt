package com.gazefollower.mobile.calibration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatmapAccumulatorTest {
    @Test
    fun gaussianSamplePeaksNearTheGazePoint() {
        val heatmap = HeatmapAccumulator(width = 40, height = 60)
        heatmap.add(0.5f, 0.5f)
        val values = heatmap.snapshot()

        assertEquals(1, heatmap.sampleCount)
        assertTrue(values[30 * 40 + 20] > values[0])
    }

    @Test
    fun clearRemovesDensityAndSampleCount() {
        val heatmap = HeatmapAccumulator(width = 40, height = 60)
        heatmap.add(0.25f, 0.75f)
        heatmap.clear()

        assertEquals(0, heatmap.sampleCount)
        assertTrue(heatmap.snapshot().all { it == 0f })
    }
}
