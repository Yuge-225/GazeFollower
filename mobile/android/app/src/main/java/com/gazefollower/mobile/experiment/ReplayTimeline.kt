package com.gazefollower.mobile.experiment

internal class ReplayTimeline(
    sourceDurationMs: Long,
    completePasses: Int,
    finalPassMs: Long,
    totalViewedMs: Long,
    val manualScroll: Boolean,
    val endedBySwipe: Boolean,
) {
    private val fullPassMs = if (completePasses > 0 && manualScroll) {
        ((totalViewedMs - finalPassMs) / completePasses).takeIf { it > 0 } ?: sourceDurationMs
    } else {
        sourceDurationMs
    }.coerceAtLeast(1L)
    private val lastPassMs = finalPassMs.coerceIn(0L, fullPassMs)
    private val passes = if (manualScroll) buildList {
        repeat(completePasses) { add(fullPassMs) }
        if (lastPassMs > 0 || isEmpty()) add(lastPassMs)
    } else listOf(if (endedBySwipe) fullPassMs else lastPassMs)

    val durationMs = passes.sum()
    val passCount = passes.size

    data class Position(val pass: Int, val startMs: Long, val videoMs: Long, val passDurationMs: Long)

    fun positionAt(timeMs: Long): Position {
        val time = timeMs.coerceIn(0L, durationMs)
        var start = 0L
        passes.forEachIndexed { index, duration ->
            if (time < start + duration || index == passes.lastIndex) {
                return Position(index + 1, start, time - start, duration)
            }
            start += duration
        }
        error("Replay always has at least one pass")
    }

    fun hasGazeAt(position: Position) = manualScroll || !endedBySwipe || position.videoMs <= lastPassMs
}
