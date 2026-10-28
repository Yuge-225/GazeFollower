package com.gazefollower.mobile.experiment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import com.gazefollower.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.hypot

class VideoExperimentView(context: Context) : FrameLayout(context) {
    var onExit: (() -> Unit)? = null

    private data class Sample(
        val videoId: String,
        val positionMs: Long,
        val x: Float,
        val y: Float,
        val videoX: Float,
        val videoY: Float,
        val strictHit: Boolean,
        val tolerantHit: Boolean,
        val distancePx: Float,
    )

    private data class TrialResult(
        val video: ExperimentVideo,
        var validSamples: Int = 0,
        var strictHits: Int = 0,
        var tolerantHits: Int = 0,
        var distanceSum: Float = 0f,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val results = mutableListOf<TrialResult>()
    private val samples = mutableListOf<Sample>()
    private var experiment: Experiment? = null
    private var trialIndex = 0
    private var videoView: VideoView? = null
    private var videoStage: View? = null
    private var collecting = false
    private var sessionId = ""
    private var detailVideoView: VideoView? = null
    private var detailTicker: Runnable? = null

    init {
        setBackgroundColor(context.getColor(R.color.background))
        visibility = GONE
    }

    fun start(experiment: Experiment) {
        this.experiment = experiment
        trialIndex = 0
        results.clear()
        samples.clear()
        sessionId = UUID.randomUUID().toString()
        visibility = VISIBLE
        showInstruction()
    }

    fun onGaze(normalizedX: Float, normalizedY: Float) {
        post {
            if (!collecting) return@post
            val exp = experiment ?: return@post
            val video = exp.videos.getOrNull(trialIndex) ?: return@post
            val player = videoView ?: return@post
            val position = player.currentPosition.toLong()
            if (position < SCORING_DELAY_MS) return@post
            val roi = video.roiAt(position) ?: return@post
            val bounds = videoContentBounds(video)
            val gazeX = normalizedX * width
            val gazeY = normalizedY * height
            val videoX = ((gazeX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            val videoY = ((gazeY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            val strict = mapRoi(roi, bounds)
            val tolerant = mapRoi(roi.expanded(0.20f), bounds)
            val strictHit = strict.contains(gazeX, gazeY)
            val tolerantHit = tolerant.contains(gazeX, gazeY)
            val dx = when {
                gazeX < strict.left -> strict.left - gazeX
                gazeX > strict.right -> gazeX - strict.right
                else -> 0f
            }
            val dy = when {
                gazeY < strict.top -> strict.top - gazeY
                gazeY > strict.bottom -> gazeY - strict.bottom
                else -> 0f
            }
            val result = results.lastOrNull() ?: return@post
            result.validSamples += 1
            if (strictHit) result.strictHits += 1
            if (tolerantHit) result.tolerantHits += 1
            result.distanceSum += hypot(dx, dy)
            samples += Sample(video.id, position, gazeX, gazeY, videoX, videoY, strictHit, tolerantHit, hypot(dx, dy))
        }
    }

    private fun showInstruction() {
        stopDetailReplay()
        collecting = false
        removeAllViews()
        val exp = experiment ?: return
        val video = exp.videos.getOrNull(trialIndex) ?: return showDashboard()
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(label("TRIAL ${trialIndex + 1} OF ${exp.videos.size}", 16f, false), params(18))
            addView(label("Look at the", 24f, false), params(6))
            addView(label(video.target.label.uppercase(), 38f, true), params(18))
            addView(label(video.displayName, 15f, false), params(30))
            addView(button("Ready") { startCountdown(video) }, params(12, 64))
            addView(button("Exit experiment") { onExit?.invoke() }, params(height = 52))
        }
        addView(content, LayoutParams(-1, -1))
    }

    private fun startCountdown(video: ExperimentVideo) {
        removeAllViews()
        val countdown = label("3", 82f, true).apply { gravity = Gravity.CENTER }
        addView(countdown, LayoutParams(-1, -1))
        listOf("3", "2", "1", "•").forEachIndexed { index, value ->
            handler.postDelayed({ countdown.text = value }, index * 500L)
        }
        handler.postDelayed({ playVideo(video) }, 2_000L)
    }

    private fun playVideo(video: ExperimentVideo) {
        removeAllViews()
        val player = VideoView(context).apply {
            setVideoPath(video.localPath)
            setOnPreparedListener { mediaPlayer ->
                mediaPlayer.isLooping = false
                results += TrialResult(video)
                collecting = true
                start()
            }
            setOnCompletionListener {
                collecting = false
                trialIndex += 1
                showInstruction()
            }
            setOnErrorListener { _, _, _ ->
                collecting = false
                trialIndex += 1
                showInstruction()
                true
            }
        }
        videoView = player
        val stage = AspectFitFrameLayout(context, video.width, video.height).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(player, LayoutParams(-1, -1))
        }
        videoStage = stage
        addView(stage, LayoutParams(-2, -2, Gravity.CENTER))
    }

    private fun showDashboard(save: Boolean = true) {
        stopDetailReplay()
        collecting = false
        videoView = null
        videoStage = null
        removeAllViews()
        if (save) saveSession()
        val totalValid = results.sumOf { it.validSamples }
        val totalStrict = results.sumOf { it.strictHits }
        val totalTolerant = results.sumOf { it.tolerantHits }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(32))
            addView(label("RESULT DASHBOARD", 28f, true), params(16))
            addView(label("Strict hit rate: ${percent(totalStrict, totalValid)}", 20f, true), params(6))
            addView(label("Tolerant hit rate: ${percent(totalTolerant, totalValid)}", 20f, true), params(6))
            addView(label("Valid scored samples: $totalValid", 16f, false), params(22))
            results.forEachIndexed { index, result ->
                val meanDistance = if (result.validSamples == 0) 0f else result.distanceSum / result.validSamples
                addView(resultCard(index, result, meanDistance), params(14))
            }
            addView(button("Return to home") { onExit?.invoke() }, params(8, 58))
        }
        addView(ScrollView(context).apply { addView(content) }, LayoutParams(-1, -1))
    }

    private fun resultCard(index: Int, result: TrialResult, meanDistance: Float): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(context.getColor(R.color.card_background))
            setStroke(dp(1), context.getColor(R.color.card_border))
        }
        addView(label("VIDEO ${index + 1} · ${result.video.target.label.uppercase()}", 18f, true).apply {
            gravity = Gravity.START
        }, params(5))
        addView(label(result.video.displayName, 14f, false).apply { gravity = Gravity.START }, params(9))
        addView(label(
            "Strict ${percent(result.strictHits, result.validSamples)}  ·  " +
                "Tolerant ${percent(result.tolerantHits, result.validSamples)}  ·  " +
                "Mean distance ${meanDistance.toInt()} px",
            14f,
            false,
        ).apply { gravity = Gravity.START }, params(10))
        addView(button("View replay, heatmap, and details") { showResultDetail(index) }, params(height = 52))
    }

    private fun showResultDetail(index: Int) {
        stopDetailReplay()
        removeAllViews()
        val result = results.getOrNull(index) ?: return showDashboard(save = false)
        val video = result.video
        val videoSamples = samples.filter { it.videoId == video.id }
        val heatmap = GazeHeatmapView(context).apply {
            setPoints(videoSamples.map { HeatmapPoint(it.positionMs, it.videoX, it.videoY) })
        }
        val player = VideoView(context).apply { setVideoPath(video.localPath) }
        detailVideoView = player
        val stage = AspectFitFrameLayout(context, video.width, video.height).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(player, LayoutParams(-1, -1))
            addView(heatmap, LayoutParams(-1, -1))
        }
        val replayArea = FrameLayout(context).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(stage, LayoutParams(-2, -2, Gravity.CENTER))
        }
        val replayTime = label("00:00.000 / ${formatTime(video.durationMs)}", 14f, false)
        val replayProgress = SeekBar(context).apply {
            max = video.durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
        lateinit var playPause: Button
        var dragging = false
        playPause = button("Pause replay") {
            if (player.isPlaying) {
                player.pause()
                playPause.text = "Resume replay"
            } else {
                player.start()
                playPause.text = "Pause replay"
            }
        }
        replayProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
                player.pause()
            }

            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                player.seekTo(value)
                replayTime.text = "${formatTime(value.toLong())} / ${formatTime(video.durationMs)}"
                heatmap.setPlaybackState(value.toLong(), video.roiAt(value.toLong()))
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                player.start()
                playPause.text = "Pause replay"
            }
        })

        val meanDistance = if (result.validSamples == 0) 0f else result.distanceSum / result.validSamples
        val metrics = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(30))
            addView(button("← Back to all videos") { showDashboard(save = false) }, params(8, 50))
            addView(label("VIDEO ${index + 1} · ${video.target.label.uppercase()}", 22f, true), params(4))
            addView(label(video.displayName, 14f, false), params(10))
            addView(replayTime, params(2))
            addView(replayProgress, params(4, 42))
            addView(playPause, params(16, 50))
            addView(metric(
                "Strict hit rate",
                percent(result.strictHits, result.validSamples),
                "Percentage of gaze samples inside the exact bounding box.",
            ), params(10))
            addView(metric(
                "Tolerant hit rate",
                percent(result.tolerantHits, result.validSamples),
                "Percentage inside a box enlarged by 20%. This includes near-edge misses.",
            ), params(10))
            addView(metric(
                "Mean distance",
                "${meanDistance.toInt()} px",
                "Average shortest distance to the exact box. Samples inside the box count as 0 px.",
            ), params(10))
            addView(metric(
                "Scored samples",
                result.validSamples.toString(),
                "Valid gaze predictions scored after the first 500 ms of this video.",
            ), params(10))
            addView(label(
                "Heatmap: warmer and brighter areas received more gaze samples. The white ring is the latest gaze point; the green rectangle is the annotated target.",
                14f,
                false,
            ), params(10))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(replayArea, LinearLayout.LayoutParams(-1, 0, 0.48f))
            addView(ScrollView(context).apply { addView(metrics) }, LinearLayout.LayoutParams(-1, 0, 0.52f))
        }
        addView(root, LayoutParams(-1, -1))

        player.setOnPreparedListener { mediaPlayer ->
            mediaPlayer.isLooping = true
            player.start()
        }
        detailTicker = object : Runnable {
            override fun run() {
                if (!dragging) {
                    val position = player.currentPosition.coerceAtLeast(0)
                    replayProgress.progress = position.coerceAtMost(replayProgress.max)
                    replayTime.text = "${formatTime(position.toLong())} / ${formatTime(video.durationMs)}"
                    heatmap.setPlaybackState(position.toLong(), video.roiAt(position.toLong()))
                }
                player.postDelayed(this, 33L)
            }
        }.also { player.post(it) }
    }

    private fun metric(name: String, value: String, explanation: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(context.getColor(R.color.card_background))
        }
        addView(label("$name · $value", 18f, true).apply { gravity = Gravity.START }, params(4))
        addView(label(explanation, 14f, false).apply { gravity = Gravity.START })
    }

    private fun stopDetailReplay() {
        detailTicker?.let { ticker -> detailVideoView?.removeCallbacks(ticker) }
        detailTicker = null
        detailVideoView?.stopPlayback()
        detailVideoView = null
    }

    private fun saveSession() {
        val exp = experiment ?: return
        val directory = File(context.filesDir, "sessions/$sessionId").apply { mkdirs() }
        val summary = JSONObject().apply {
            put("sessionId", sessionId)
            put("experimentName", exp.name)
            put("experimentVersion", exp.version)
            put("trials", JSONArray().apply {
                results.forEach { result ->
                    put(JSONObject().apply {
                        put("videoId", result.video.id)
                        put("videoName", result.video.displayName)
                        put("target", result.video.target.name)
                        put("validSamples", result.validSamples)
                        put("strictHits", result.strictHits)
                        put("tolerantHits", result.tolerantHits)
                        put("meanDistancePx", if (result.validSamples == 0) 0.0 else result.distanceSum / result.validSamples)
                    })
                }
            })
        }
        File(directory, "session_summary.json").writeText(summary.toString(2))
        File(directory, "gaze_samples.csv").bufferedWriter().use { writer ->
            writer.appendLine("video_id,video_time_ms,gaze_x_px,gaze_y_px,video_x_normalized,video_y_normalized,strict_hit,tolerant_hit,distance_px")
            samples.forEach { sample ->
                writer.appendLine("${sample.videoId},${sample.positionMs},${sample.x},${sample.y},${sample.videoX},${sample.videoY},${sample.strictHit},${sample.tolerantHit},${sample.distancePx}")
            }
        }
    }

    private fun videoContentBounds(video: ExperimentVideo): RectF {
        videoStage?.let { stage ->
            return RectF(
                stage.left.toFloat(),
                stage.top.toFloat(),
                stage.right.toFloat(),
                stage.bottom.toFloat(),
            )
        }
        val videoWidth = video.width.coerceAtLeast(1)
        val videoHeight = video.height.coerceAtLeast(1)
        val scale = kotlin.math.min(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val drawWidth = videoWidth * scale
        val drawHeight = videoHeight * scale
        val left = (width - drawWidth) / 2f
        val top = (height - drawHeight) / 2f
        return RectF(left, top, left + drawWidth, top + drawHeight)
    }

    private fun mapRoi(roi: NormalizedRoi, content: RectF) = RectF(
        content.left + roi.left * content.width(),
        content.top + roi.top * content.height(),
        content.left + roi.right * content.width(),
        content.top + roi.bottom * content.height(),
    )

    private fun label(textValue: String, size: Float, primary: Boolean) = TextView(context).apply {
        text = textValue
        textSize = size
        gravity = Gravity.CENTER
        setTextColor(context.getColor(if (primary) R.color.foreground else R.color.secondary))
    }

    private fun button(textValue: String, click: () -> Unit) = Button(context).apply {
        text = textValue
        textSize = 17f
        isAllCaps = false
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.button_text), context.getColor(R.color.button_text_disabled)),
        ))
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(context.getColor(R.color.button_background), context.getColor(R.color.button_background_disabled)),
        )
        setOnClickListener { click() }
    }

    private fun params(bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
        val resolvedHeight = if (height == ViewGroup.LayoutParams.WRAP_CONTENT) height else dp(height)
        return LinearLayout.LayoutParams(-1, resolvedHeight).apply { bottomMargin = dp(bottom) }
    }

    private fun percent(hit: Int, total: Int): String = if (total == 0) "--" else "%.1f%%".format(hit * 100.0 / total)
    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return "%02d:%02d.%03d".format(safe / 60_000, (safe / 1_000) % 60, safe % 1_000)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCORING_DELAY_MS = 500L
    }
}
