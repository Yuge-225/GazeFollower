package com.gazefollower.mobile

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.VideoView
import com.gazefollower.mobile.experiment.AspectFitFrameLayout
import com.gazefollower.mobile.experiment.ExperimentStore
import com.gazefollower.mobile.experiment.GazeHeatmapView
import com.gazefollower.mobile.experiment.ReplayTimeline
import com.gazefollower.mobile.experiment.TrialReplay
import org.json.JSONArray
import com.gazefollower.mobile.experiment.HeatmapPoint
import org.json.JSONObject

class VideoReplayActivity : VersionUiActivity() {
    private var replay: TrialReplay? = null

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
        val mode = summary.savedPlaybackMode()
        val viewed = trial.optLong("presentedDurationMs", video.durationMs)
        val timeline = ReplayTimeline(
            video.durationMs, trial.optInt("completedLoops"),
            trial.optLong("finalVideoTimeMs", viewed), viewed,
            mode == MODE_MANUAL_SCROLL, trial.optBoolean("endedBySwipe"),
        )
        val captured = directory.resolve("captured_samples.json")
        val points = if (captured.isFile) {
            readPasses(JSONArray(captured.readText()), trial.optInt("trialIndex", trialObjects.indexOf(trial) + 1), videoId)
        } else {
            mapOf(1 to readPoints(directory.resolve("gaze_samples.csv").readText(), videoId))
        }
        val heatmap = GazeHeatmapView(this).apply {
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
        val time = body("").apply { gravity = Gravity.CENTER }
        val progress = SeekBar(this)
        val playPause = action("Pause Replay") {}

        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(20))
            addView(title(video.displayName, 19f), row(3))
            addView(body(mode.displayMode()), row(10))
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
                addView(body("Total viewed · ${formatMs(viewed)}"), row(3))
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

        replay = TrialReplay(videoView, timeline, progress, time, playPause, heatmap, unavailableOverlay, video, points)
            .also { it.start() }
    }

    private fun readPasses(samples: JSONArray, trialIndex: Int, videoId: String): Map<Int, List<HeatmapPoint>> =
        (0 until samples.length()).asSequence().mapNotNull { index ->
            val sample = samples.optJSONObject(index) ?: return@mapNotNull null
            if (sample.optString("videoId") != videoId || !sample.optBoolean("scored")) return@mapNotNull null
            if (sample.has("trialIndex") && sample.optInt("trialIndex") != trialIndex) return@mapNotNull null
            val x = sample.optDouble("gazeXVideo")
            val y = sample.optDouble("gazeYVideo")
            val timestamp = sample.optLong("videoTimeMs", -1)
            if (!x.isFinite() || !y.isFinite() || timestamp < 0) return@mapNotNull null
            sample.optInt("playbackPass", 1) to HeatmapPoint(timestamp, x.toFloat(), y.toFloat())
        }.groupBy({ it.first }, { it.second })

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
        replay?.stop()
        replay = null
        super.onDestroy()
    }
}
