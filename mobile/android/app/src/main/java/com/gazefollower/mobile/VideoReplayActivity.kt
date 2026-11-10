package com.gazefollower.mobile

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.gazefollower.mobile.experiment.AspectFitFrameLayout
import com.gazefollower.mobile.experiment.ExperimentStore
import com.gazefollower.mobile.experiment.GazeHeatmapView
import com.gazefollower.mobile.experiment.HeatmapPoint
import org.json.JSONObject

class VideoReplayActivity : VersionUiActivity() {
    private var player: VideoView? = null
    private var ticker: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        val store = ExperimentStore(this)
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID).orEmpty()
        val directory = store.sessionDirectory(sessionId)
        val summaryFile = directory.resolve("session_summary.json")
        if (!summaryFile.exists()) {
            show(page("Replay", "The saved test result is unavailable."))
            return
        }
        val summary = JSONObject(summaryFile.readText())
        val experiment = summary.optJSONObject("experimentSnapshot")
            ?.let(store::fromSnapshot)
            ?: store.load(intent.getStringExtra(EXTRA_VERSION_ID))
        val video = experiment.videos.firstOrNull { it.id == videoId }
        val trials = summary.optJSONArray("trials")
        val trialObjects = (0 until (trials?.length() ?: 0))
            .map { trials!!.getJSONObject(it) }
        val trial = trialObjects.firstOrNull { it.optString("videoId") == videoId }
            ?: trialObjects.getOrNull(intent.getIntExtra(EXTRA_TRIAL_INDEX, -1))
        if (video == null || trial == null) {
            show(page("Replay", "The saved video configuration is unavailable."))
            return
        }
        val cutoff = trial.optLong("presentedDurationMs", video.durationMs).coerceIn(0L, video.durationMs)
        val endedBySwipe = summary.optString("presentationMode") == MODE_SCROLL && trial.optBoolean("endedBySwipe", false)
        val replayEnd = if (endedBySwipe) video.durationMs else cutoff
        val points = readPoints(directory.resolve("gaze_samples.csv").readText(), videoId)
        val heatmap = GazeHeatmapView(this).apply {
            setPoints(points)
            setLabelColors(experiment.labels.associate { it.id to it.colorArgb })
            setLabelNames(experiment.labels.associate { it.id to it.name })
        }
        val unavailableOverlay = title("GAZE DATA NOT AVAILABLE\nAFTER SWIPE", 18f).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xD905080D.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            visibility = View.GONE
        }
        val videoView = VideoView(this).apply { setVideoPath(video.localPath) }
        player = videoView
        val stage = AspectFitFrameLayout(this, video.width, video.height).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(videoView, FrameLayout.LayoutParams(-1, -1))
            addView(heatmap, FrameLayout.LayoutParams(-1, -1))
            addView(unavailableOverlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val replayArea = FrameLayout(this).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(stage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val time = body("00:00.000 / ${formatMs(replayEnd)}").apply { gravity = Gravity.CENTER }
        val progress = SeekBar(this).apply { max = replayEnd.coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
        var dragging = false
        lateinit var playPause: Button
        playPause = action("Pause Replay") {
            if (videoView.isPlaying) {
                videoView.pause()
                playPause.text = "Resume Replay"
            } else {
                if (videoView.currentPosition >= replayEnd) videoView.seekTo(0)
                videoView.start()
                playPause.text = "Pause Replay"
            }
        }
        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
                videoView.pause()
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                videoView.seekTo(value)
                updateReplay(value.toLong(), cutoff, replayEnd, endedBySwipe, video, heatmap, unavailableOverlay, time)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                videoView.start()
                playPause.text = "Pause Replay"
            }
        })

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(20))
            addView(title(video.displayName, 19f), row(3))
            addView(body(if (endedBySwipe) {
                "Scroll Mode · Swiped at ${formatMs(cutoff)}. Replay continues, but gaze data is unavailable after the swipe."
            } else {
                "${if (summary.optString("presentationMode") == MODE_SCROLL) "Scroll Mode" else "Ready Mode"} · Full scored presentation"
            }), row(10))
            addView(time, row())
            addView(progress, row(5, 42))
            addView(playPause, row(10, 44))
            addView(title("Attention", 18f), row(6))
            val attention = trial.optJSONArray("attentionByLabel")
            if (attention != null && attention.length() > 0) {
                for (index in 0 until attention.length()) {
                    val item = attention.getJSONObject(index)
                    addView(attentionRow(item.optString("labelName"), item.optInt("samples"), trial.optInt("validSamples")), row(6))
                }
            } else {
                addView(body("Attention distribution was not recorded for this earlier test."), row(8))
            }
            val technical = LinearLayout(this@VideoReplayActivity).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                addView(body("Presented · ${formatMs(cutoff)} / ${formatMs(video.durationMs)}"), row(3))
                addView(body("Captured · ${trial.optInt("capturedGazeSamples")} · Valid · ${trial.optInt("validSamples")}"), row(3))
                addView(body("Inside exact AOIs · ${fraction(trial.optInt("strictHits"), trial.optInt("validSamples"))}"), row(3))
                addView(body("Inside tolerant AOIs · ${fraction(trial.optInt("tolerantHits"), trial.optInt("validSamples"))}"), row(3))
                addView(body("Mean distance · ${trial.optDouble("meanDistancePx", 0.0).toInt()} px"))
            }
            val technicalToggle = action("Show Technical Details") {}
            technicalToggle.setOnClickListener {
                val show = technical.visibility != View.VISIBLE
                technical.visibility = if (show) View.VISIBLE else View.GONE
                technicalToggle.text = if (show) "Hide Technical Details" else "Show Technical Details"
            }
            addView(technicalToggle, row(5, 42))
            addView(technical, row(8))
            addView(action("Back") { finish() }, row(height = 44))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            addView(replayArea, LinearLayout.LayoutParams(-1, 0, 0.58f))
            addView(ScrollView(this@VideoReplayActivity).apply { addView(metrics) }, LinearLayout.LayoutParams(-1, 0, 0.42f))
        })

        videoView.setOnPreparedListener {
            it.isLooping = false
            videoView.start()
        }
        ticker = object : Runnable {
            override fun run() {
                if (!dragging) {
                    val position = videoView.currentPosition.toLong().coerceAtLeast(0L)
                    if (position >= replayEnd) {
                        videoView.pause()
                        videoView.seekTo(replayEnd.toInt())
                        playPause.text = "Replay Again"
                    }
                    val shown = position.coerceAtMost(replayEnd)
                    progress.progress = shown.toInt()
                    updateReplay(shown, cutoff, replayEnd, endedBySwipe, video, heatmap, unavailableOverlay, time)
                }
                videoView.postDelayed(this, 33L)
            }
        }.also(videoView::post)
    }

    private fun updateReplay(
        position: Long,
        cutoff: Long,
        replayEnd: Long,
        endedBySwipe: Boolean,
        video: com.gazefollower.mobile.experiment.ExperimentVideo,
        heatmap: GazeHeatmapView,
        unavailableOverlay: TextView,
        time: TextView,
    ) {
        time.text = "${formatMs(position)} / ${formatMs(replayEnd)}"
        val scoredPosition = position.coerceAtMost(cutoff)
        heatmap.setPlaybackState(scoredPosition, video.activeAoisAt(scoredPosition))
        unavailableOverlay.visibility = if (endedBySwipe && position > cutoff) View.VISIBLE else View.GONE
    }

    private fun readPoints(csv: String, videoId: String): List<HeatmapPoint> = csv.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val values = line.split(',', limit = 10)
            if (values.size < 6 || values[0] != videoId) null else {
                val timestamp = values[1].toLongOrNull()
                val x = values[4].toFloatOrNull()
                val y = values[5].toFloatOrNull()
                if (timestamp == null || x == null || y == null) null else HeatmapPoint(timestamp, x, y)
            }
        }
        .toList()

    private fun formatMs(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return "%02d:%02d.%03d".format(safe / 60_000, (safe / 1_000) % 60, safe % 1_000)
    }

    override fun onDestroy() {
        ticker?.let { player?.removeCallbacks(it) }
        ticker = null
        player?.stopPlayback()
        player = null
        super.onDestroy()
    }
}
