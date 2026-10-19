package com.gazefollower.mobile.calibration

import org.junit.Assert.assertEquals
import org.junit.Test

class RidgeCalibrationTest {
    @Test
    fun fitsTwoOutputLinearMapping() {
        val features = mutableListOf<FloatArray>()
        val labels = mutableListOf<FloatArray>()
        for (x in -3..3) {
            for (y in -2..2) {
                features += floatArrayOf(x.toFloat(), y.toFloat(), (x - y).toFloat())
                labels += floatArrayOf(0.25f + 0.08f * x - 0.03f * y, 0.6f - 0.04f * x + 0.1f * y)
            }
        }
        val model = RidgeCalibration(alpha = 1e-6)
        model.fit(features, labels)
        val prediction = model.predict(floatArrayOf(1f, -1f, 2f))
        assertEquals(0.36f, prediction[0], 1e-3f)
        assertEquals(0.46f, prediction[1], 1e-3f)
    }
}
