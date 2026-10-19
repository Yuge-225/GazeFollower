package com.gazefollower.mobile.calibration

import kotlin.math.abs

/** Multi-output ridge regression with an unregularized intercept. */
class RidgeCalibration(private val alpha: Double = 0.1) {
    private var weights: Array<DoubleArray>? = null
    private var bias = DoubleArray(2)

    fun fit(features: List<FloatArray>, labels: List<FloatArray>) {
        require(features.isNotEmpty() && features.size == labels.size)
        val n = features.size
        val d = features.first().size
        require(features.all { it.size == d } && labels.all { it.size == 2 })

        val xMean = DoubleArray(d)
        val yMean = DoubleArray(2)
        features.forEachIndexed { row, feature ->
            for (column in 0 until d) xMean[column] += feature[column] / n.toDouble()
            for (axis in 0..1) yMean[axis] += labels[row][axis] / n.toDouble()
        }
        val centeredX = Array(n) { row ->
            DoubleArray(d) { column -> features[row][column] - xMean[column] }
        }
        val centeredY = Array(n) { row ->
            DoubleArray(2) { axis -> labels[row][axis] - yMean[axis] }
        }

        // Dual form avoids inverting a 259x259 matrix when calibration has far
        // fewer samples than model features: W = X' (X X' + alpha I)^-1 Y.
        val kernel = Array(n) { first ->
            DoubleArray(n) { second ->
                var sum = 0.0
                for (column in 0 until d) {
                    sum += centeredX[first][column] * centeredX[second][column]
                }
                sum + if (first == second) alpha else 0.0
            }
        }
        val coefficients = solve(kernel, centeredY)
        val fittedWeights = Array(d) { column ->
            DoubleArray(2) { axis ->
                var sum = 0.0
                for (row in 0 until n) sum += centeredX[row][column] * coefficients[row][axis]
                sum
            }
        }
        bias = DoubleArray(2) { axis ->
            var value = yMean[axis]
            for (column in 0 until d) value -= xMean[column] * fittedWeights[column][axis]
            value
        }
        weights = fittedWeights
    }

    fun predict(features: FloatArray): FloatArray {
        val fittedWeights = checkNotNull(weights) { "Calibration has not been fitted" }
        require(features.size == fittedWeights.size)
        return FloatArray(2) { axis ->
            var value = bias[axis]
            for (column in features.indices) value += features[column] * fittedWeights[column][axis]
            value.toFloat()
        }
    }

    private fun solve(matrix: Array<DoubleArray>, right: Array<DoubleArray>): Array<DoubleArray> {
        val n = matrix.size
        val augmented = Array(n) { row ->
            DoubleArray(n + 2).also { values ->
                matrix[row].copyInto(values, endIndex = n)
                values[n] = right[row][0]
                values[n + 1] = right[row][1]
            }
        }
        for (pivot in 0 until n) {
            var best = pivot
            for (row in pivot + 1 until n) {
                if (abs(augmented[row][pivot]) > abs(augmented[best][pivot])) best = row
            }
            require(abs(augmented[best][pivot]) > 1e-12) { "Calibration matrix is singular" }
            val swap = augmented[pivot]
            augmented[pivot] = augmented[best]
            augmented[best] = swap

            val divisor = augmented[pivot][pivot]
            for (column in pivot until n + 2) augmented[pivot][column] /= divisor
            for (row in 0 until n) {
                if (row == pivot) continue
                val factor = augmented[row][pivot]
                if (factor == 0.0) continue
                for (column in pivot until n + 2) {
                    augmented[row][column] -= factor * augmented[pivot][column]
                }
            }
        }
        return Array(n) { row -> doubleArrayOf(augmented[row][n], augmented[row][n + 1]) }
    }
}
