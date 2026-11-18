package com.gazefollower.mobile.experiment

import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale

internal class TrialReplay(
    private val player: VideoView,
    private val timeline: ReplayTimeline,
    private val progress: SeekBar,
    private val time: TextView,
    private val playPause: Button,
    private val heatmap: GazeHeatmapView,
    private val unavailable: View,
    private val video: ExperimentVideo,
    private val pointsByPass: Map<Int, List<HeatmapPoint>>,
) {
    private var current = timeline.positionAt(0)
    private var displayPass = 0
    private var ready = false
    private var playing = false
    private var dragging = false
    private var seeking = false
    private var ended = false

    private val tick = object : Runnable {
        override fun run() {
            if (ready && playing && !seeking && !dragging) {
                val videoMs = player.currentPosition.toLong()
                if (videoMs >= current.passDurationMs) advance()
                else render(current.startMs + videoMs)
            }
            player.postDelayed(this, 33L)
        }
    }

    fun start() {
        progress.max = timeline.durationMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        playPause.isEnabled = false
        progress.isEnabled = false
        render(0)
        playPause.setOnClickListener {
            when {
                ended -> { playing = true; seekTo(0) }
                playing -> { playing = false; player.pause() }
                else -> { playing = true; if (!seeking) player.start() }
            }
            updateButton()
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
                player.pause()
            }

            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                if (dragging) render(value.toLong()) else seekTo(value.toLong())
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                seekTo(seekBar.progress.toLong())
            }
        })
        player.setOnPreparedListener { media ->
            media.isLooping = false
            media.setOnSeekCompleteListener {
                seeking = false
                if (playing && !dragging && !ended) player.start()
            }
            ready = true
            playPause.isEnabled = timeline.durationMs > 0
            progress.isEnabled = timeline.durationMs > 0
            playing = timeline.durationMs > 0
            if (playing) player.start()
            updateButton()
        }
        player.setOnCompletionListener {
            if (playing && !seeking && !dragging) advance()
        }
        player.post(tick)
    }

    private fun advance() {
        val endOfPass = current.startMs + current.passDurationMs
        if (endOfPass < timeline.durationMs) seekTo(endOfPass)
        else finish()
    }

    private fun seekTo(timeMs: Long) {
        if (timeMs >= timeline.durationMs) {
            finish()
            player.seekTo(current.videoMs.toInt())
            return
        }
        ended = false
        seeking = true
        player.pause()
        render(timeMs)
        player.seekTo(current.videoMs.toInt())
        updateButton()
    }

    private fun finish() {
        ended = true
        playing = false
        player.pause()
        render(timeline.durationMs)
        updateButton()
    }

    private fun updateButton() {
        playPause.text = when {
            ended -> "Replay again"
            playing -> "Pause replay"
            else -> "Resume replay"
        }
    }

    private fun render(timeMs: Long) {
        current = timeline.positionAt(timeMs)
        if (!dragging) progress.progress = timeMs.coerceAtMost(progress.max.toLong()).toInt()
        val hasGaze = timeline.hasGazeAt(current)
        if (displayPass != current.pass) {
            displayPass = current.pass
            heatmap.setPoints(pointsByPass[displayPass].orEmpty())
        }
        heatmap.setPlaybackState(
            if (hasGaze) current.videoMs else -1L,
            if (hasGaze) video.activeAoisAt(current.videoMs) else emptyList(),
        )
        unavailable.visibility = if (hasGaze) View.GONE else View.VISIBLE
        val elapsed = "${format(timeMs)} / ${format(timeline.durationMs)}"
        time.text = if (timeline.manualScroll) {
            "Round ${current.pass} of ${timeline.passCount} · ${format(current.videoMs)} / ${format(current.passDurationMs)}\nTotal $elapsed"
        } else elapsed
    }

    private fun format(ms: Long) = String.format(Locale.ROOT, "%02d:%02d.%03d", ms / 60_000, ms / 1_000 % 60, ms % 1_000)

    fun stop() {
        player.removeCallbacks(tick)
        player.stopPlayback()
    }
}
