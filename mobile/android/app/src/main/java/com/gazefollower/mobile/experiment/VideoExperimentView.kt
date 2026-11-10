package com.gazefollower.mobile.experiment

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.VideoView
import com.gazefollower.mobile.R
import com.gazefollower.mobile.MODE_READY
import com.gazefollower.mobile.MODE_SCROLL
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
        val matchedAois: String,
        val assignedLabelId: String,
    )

    private data class TrialResult(
        val video: ExperimentVideo,
        var capturedGazeSamples: Int = 0,
        var validSamples: Int = 0,
        var strictHits: Int = 0,
        var tolerantHits: Int = 0,
        var distanceSum: Float = 0f,
        var presentedDurationMs: Long = 0L,
        var endedBySwipe: Boolean = false,
        val attentionCounts: MutableMap<String, Int> = mutableMapOf(),
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
    private var presentationMode = MODE_READY
    private var startedAtMs = 0L
    private var detailVideoView: VideoView? = null
    private var detailTicker: Runnable? = null

    init {
        setBackgroundColor(context.getColor(R.color.background))
        visibility = GONE
    }

    fun start(experiment: Experiment, mode: String = MODE_READY) {
        this.experiment = experiment
        presentationMode = if (mode == MODE_SCROLL) MODE_SCROLL else MODE_READY
        trialIndex = 0
        results.clear()
        samples.clear()
        sessionId = UUID.randomUUID().toString()
        startedAtMs = System.currentTimeMillis()
        visibility = VISIBLE
        showInstruction()
    }

    fun onGaze(normalizedX: Float, normalizedY: Float) {
        post {
            if (!collecting) return@post
            val exp = experiment ?: return@post
            val video = exp.videos.getOrNull(trialIndex) ?: return@post
            val player = videoView ?: return@post
            val result = results.lastOrNull() ?: return@post
            result.capturedGazeSamples += 1
            val position = player.currentPosition.toLong()
            if (position < SCORING_DELAY_MS) return@post
            val activeAois = video.activeAoisAt(position)
            if (activeAois.isEmpty()) return@post
            val bounds = videoContentBounds(video)
            val gazeX = normalizedX * width
            val gazeY = normalizedY * height
            val videoX = ((gazeX - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            val videoY = ((gazeY - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            val strictRegions = activeAois.map { (target, roi) -> target to mapRoi(roi, bounds) }
            val tolerantRegions = activeAois.map { (target, roi) -> target to mapRoi(roi.expanded(0.20f), bounds) }
            val strictHit = strictRegions.any { (_, region) -> region.contains(gazeX, gazeY) }
            val tolerantHit = tolerantRegions.any { (_, region) -> region.contains(gazeX, gazeY) }
            val distance = strictRegions.minOf { (_, region) ->
                val dx = when {
                    gazeX < region.left -> region.left - gazeX
                    gazeX > region.right -> gazeX - region.right
                    else -> 0f
                }
                val dy = when {
                    gazeY < region.top -> region.top - gazeY
                    gazeY > region.bottom -> gazeY - region.bottom
                    else -> 0f
                }
                hypot(dx, dy)
            }
            val assignedLabelId = strictRegions
                .filter { (_, region) -> region.contains(gazeX, gazeY) }
                .minByOrNull { (_, region) -> region.width() * region.height() }
                ?.first
                ?: OUTSIDE_LABEL_ID
            val matchedAois = if (assignedLabelId == OUTSIDE_LABEL_ID) {
                "Outside AOIs"
            } else {
                exp.labels.firstOrNull { it.id == assignedLabelId }?.name ?: assignedLabelId
            }
            result.validSamples += 1
            if (strictHit) result.strictHits += 1
            if (tolerantHit) result.tolerantHits += 1
            result.distanceSum += distance
            result.attentionCounts[assignedLabelId] = (result.attentionCounts[assignedLabelId] ?: 0) + 1
            samples += Sample(video.id, position, gazeX, gazeY, videoX, videoY, strictHit, tolerantHit, distance, matchedAois, assignedLabelId)
        }
    }

    private fun showInstruction() {
        stopDetailReplay()
        collecting = false
        removeAllViews()
        val exp = experiment ?: return
        val video = exp.videos.getOrNull(trialIndex) ?: return showDashboard()
        if (presentationMode == MODE_SCROLL && trialIndex > 0) {
            playVideo(video)
            return
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(label(if (presentationMode == MODE_SCROLL) "SCROLL MODE" else "VIDEO ${trialIndex + 1} OF ${exp.videos.size}", 16f, false), params(18))
            addView(label(if (presentationMode == MODE_SCROLL) "Swipe up for the next video" else "Ready for the next video?", 25f, true), params(10))
            addView(label(if (presentationMode == MODE_SCROLL) "The current video stops scoring as soon as the swipe is accepted." else video.displayName, 15f, false), params(30))
            addView(button(if (presentationMode == MODE_SCROLL) "Start Scroll Test" else "Ready") {
                if (presentationMode == MODE_SCROLL) playVideo(video) else startCountdown(video)
            }, params(12, 64))
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
                finishCurrentVideo(swiped = false)
            }
            setOnErrorListener { _, _, _ ->
                finishCurrentVideo(swiped = false)
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
        if (presentationMode == MODE_SCROLL) installSwipeGesture(stage)
    }

    private fun installSwipeGesture(stage: View) {
        var downY = 0f
        stage.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - downY).coerceAtMost(0f)
                    view.translationY = delta * 0.35f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val delta = event.rawY - downY
                    if (delta < -dp(72)) {
                        collecting = false
                        val cutoffAtSwipe = videoView?.currentPosition?.toLong()?.coerceAtLeast(0L)
                        view.animate().translationY(-height.toFloat()).alpha(0f).setDuration(180L)
                            .withEndAction { finishCurrentVideo(swiped = true, cutoffMs = cutoffAtSwipe) }.start()
                    } else {
                        view.animate().translationY(0f).alpha(1f).setDuration(140L).start()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.animate().translationY(0f).alpha(1f).setDuration(140L).start()
                    true
                }
                else -> false
            }
        }
    }

    private fun finishCurrentVideo(swiped: Boolean, cutoffMs: Long? = null) {
        val player = videoView
        val result = results.lastOrNull()
        collecting = false
        result?.presentedDurationMs = if (swiped) {
            cutoffMs ?: player?.currentPosition?.toLong()?.coerceAtLeast(0L) ?: 0L
        } else {
            result?.video?.durationMs ?: 0L
        }
        result?.endedBySwipe = swiped
        player?.stopPlayback()
        videoView = null
        videoStage = null
        trialIndex += 1
        showInstruction()
    }

    private fun showDashboard(save: Boolean = true) {
        stopDetailReplay()
        collecting = false
        videoView = null
        videoStage = null
        removeAllViews()
        if (save) saveSession()
        val totalValid = results.sumOf { it.validSamples }
        val totalCaptured = results.sumOf { it.capturedGazeSamples }
        val totalStrict = results.sumOf { it.strictHits }
        val totalTolerant = results.sumOf { it.tolerantHits }
        val totalAttention = mutableMapOf<String, Int>()
        results.forEach { result -> result.attentionCounts.forEach { (id, count) ->
            totalAttention[id] = (totalAttention[id] ?: 0) + count
        } }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            addView(label("Test Result", 26f, true).apply { gravity = Gravity.START }, params(3))
            addView(label("${if (presentationMode == MODE_SCROLL) "SCROLL MODE" else "READY MODE"} · ${results.size} videos · $totalValid valid samples", 14f, false).apply { gravity = Gravity.START }, params(16))
            addView(label("Attention", 20f, true).apply { gravity = Gravity.START }, params(8))
            totalAttention.entries.sortedByDescending { it.value }.forEach { (id, count) ->
                addView(attentionBar(labelName(id), count, totalValid), params(7))
            }
            val technical = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
                addView(label("Captured gaze samples · $totalCaptured", 14f, false).apply { gravity = Gravity.START }, params(3))
                addView(label("Inside exact AOIs · ${hitFraction(totalStrict, totalValid)}", 14f, false).apply { gravity = Gravity.START }, params(3))
                addView(label("Inside tolerant AOIs · ${hitFraction(totalTolerant, totalValid)}", 14f, false).apply { gravity = Gravity.START })
            }
            val toggle = button("Show Technical Details") {}
            toggle.setOnClickListener {
                val show = technical.visibility != VISIBLE
                technical.visibility = if (show) VISIBLE else GONE
                toggle.text = if (show) "Hide Technical Details" else "Show Technical Details"
            }
            addView(toggle, params(5, 44))
            addView(technical, params(14))
            addView(label("Videos", 20f, true).apply { gravity = Gravity.START }, params(8))
            results.forEachIndexed { index, result ->
                addView(resultCard(index, result), params(9))
            }
            addView(button("Return to Home") { onExit?.invoke() }, params(8, 46))
        }
        addView(ScrollView(context).apply { addView(content) }, LayoutParams(-1, -1))
    }

    private fun resultCard(index: Int, result: TrialResult): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(context.getColor(R.color.card_background))
            setStroke(dp(1), context.getColor(R.color.card_border))
        }
        isClickable = true
        isFocusable = true
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label("${index + 1}. ${result.video.displayName}", 16f, true).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("›", 22f, true), LinearLayout.LayoutParams(-2, -2))
        }, params(4))
        addView(label("${if (presentationMode == MODE_SCROLL) "SCROLL" else "READY"} · ${result.validSamples} valid samples", 13f, false).apply { gravity = Gravity.START }, params(5))
        if (presentationMode == MODE_SCROLL) {
            addView(label(
                if (result.endedBySwipe) "Swiped at ${formatTime(result.presentedDurationMs)} · later gaze data unavailable"
                else "Video completed before a swipe",
                13f,
                false,
            ).apply { gravity = Gravity.START }, params(8))
        }
        addView(label(attentionSummary(result).lineSequence().take(2).joinToString(" · "), 13f, false).apply { gravity = Gravity.START })
        setOnClickListener { showResultDetail(index) }
    }

    private fun attentionBar(name: String, count: Int, total: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label(name, 14f, true).apply { gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(label("${percent(count, total)} · $count", 13f, false), LinearLayout.LayoutParams(-2, -2))
        })
        addView(ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (total == 0) 0 else count * 1000 / total
            progressTintList = ColorStateList.valueOf(context.getColor(R.color.button_background))
            progressBackgroundTintList = ColorStateList.valueOf(context.getColor(R.color.card_border))
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(3) })
    }

    private fun showResultDetail(index: Int) {
        stopDetailReplay()
        removeAllViews()
        val result = results.getOrNull(index) ?: return showDashboard(save = false)
        val video = result.video
        val videoSamples = samples.filter { it.videoId == video.id }
        val heatmap = GazeHeatmapView(context).apply {
            setPoints(videoSamples.map { HeatmapPoint(it.positionMs, it.videoX, it.videoY) })
            setLabelColors(experiment?.labels?.associate { it.id to it.colorArgb }.orEmpty())
            setLabelNames(experiment?.labels?.associate { it.id to it.name }.orEmpty())
        }
        val cutoff = result.presentedDurationMs.takeIf { it > 0L } ?: video.durationMs
        val showUnavailableAfterCutoff = presentationMode == MODE_SCROLL && result.endedBySwipe
        val unavailableOverlay = label("GAZE DATA NOT AVAILABLE\nAFTER SWIPE", 18f, true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xD905080D.toInt())
            setPadding(dp(18), dp(12), dp(18), dp(12))
            visibility = GONE
        }
        val player = VideoView(context).apply { setVideoPath(video.localPath) }
        detailVideoView = player
        val stage = AspectFitFrameLayout(context, video.width, video.height).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(player, LayoutParams(-1, -1))
            addView(heatmap, LayoutParams(-1, -1))
            addView(unavailableOverlay, LayoutParams(-2, -2, Gravity.CENTER))
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
        fun updateReplay(position: Long) {
            replayTime.text = "${formatTime(position)} / ${formatTime(video.durationMs)}"
            heatmap.setPlaybackState(position.coerceAtMost(cutoff), video.activeAoisAt(position.coerceAtMost(cutoff)))
            unavailableOverlay.visibility = if (showUnavailableAfterCutoff && position > cutoff) VISIBLE else GONE
        }
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
                updateReplay(value.toLong())
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                player.start()
                playPause.text = "Pause replay"
            }
        })

        val meanDistance = if (result.validSamples == 0) 0f else result.distanceSum / result.validSamples
        val excludedSamples = (result.capturedGazeSamples - result.validSamples).coerceAtLeast(0)
        val metrics = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(20))
            addView(label(video.displayName, 19f, true).apply { gravity = Gravity.START }, params(3))
            addView(label("${if (presentationMode == MODE_SCROLL) "SCROLL MODE" else "READY MODE"}", 13f, false).apply { gravity = Gravity.START }, params(6))
            if (showUnavailableAfterCutoff) {
                addView(label("Swiped at ${formatTime(cutoff)} · gaze data after this point was not collected or scored.", 14f, false), params(10))
            }
            addView(replayTime, params(2))
            addView(replayProgress, params(4, 42))
            addView(playPause, params(10, 44))
            addView(sectionLabel("ATTENTION"), params(7))
            result.attentionCounts.entries.sortedByDescending { it.value }.forEach { (labelId, count) ->
                addView(attentionBar(labelName(labelId), count, result.validSamples), params(6))
            }
            val technical = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                visibility = GONE
                addView(label("Source frames · ${frameCount(video)}", 13f, false).apply { gravity = Gravity.START }, params(3))
                addView(label("Captured · ${result.capturedGazeSamples} · Valid · ${result.validSamples} · Excluded · $excludedSamples", 13f, false).apply { gravity = Gravity.START }, params(3))
                addView(label("Inside exact AOIs · ${hitFraction(result.strictHits, result.validSamples)}", 13f, false).apply { gravity = Gravity.START }, params(3))
                addView(label("Inside tolerant AOIs · ${hitFraction(result.tolerantHits, result.validSamples)}", 13f, false).apply { gravity = Gravity.START }, params(3))
                addView(label("Mean distance · ${meanDistance.toInt()} px", 13f, false).apply { gravity = Gravity.START })
            }
            val toggle = button("Show Technical Details") {}
            toggle.setOnClickListener {
                val show = technical.visibility != VISIBLE
                technical.visibility = if (show) VISIBLE else GONE
                toggle.text = if (show) "Hide Technical Details" else "Show Technical Details"
            }
            addView(toggle, params(5, 42))
            addView(technical, params(7))
            addView(button("Back") { showDashboard(save = false) }, params(height = 44))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(replayArea, LinearLayout.LayoutParams(-1, 0, 0.58f))
            addView(ScrollView(context).apply { addView(metrics) }, LinearLayout.LayoutParams(-1, 0, 0.42f))
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
                    updateReplay(position.toLong())
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

    private fun sectionLabel(textValue: String) = label(textValue, 15f, false).apply {
        gravity = Gravity.START
    }

    private fun attentionSummary(result: TrialResult): String {
        if (result.validSamples == 0 || result.attentionCounts.isEmpty()) return "Attention: no valid samples"
        return result.attentionCounts.entries.sortedByDescending { it.value }.joinToString("\n") { (id, count) ->
            "${labelName(id)}  $count / ${result.validSamples} (${percent(count, result.validSamples)})"
        }
    }

    private fun labelName(id: String): String = if (id == OUTSIDE_LABEL_ID) {
        "Outside AOIs"
    } else {
        experiment?.labels?.firstOrNull { it.id == id }?.name ?: id
    }

    private fun dwellMs(videoId: String, labelId: String): Long {
        val ordered = samples.filter { it.videoId == videoId }.sortedBy { it.positionMs }
        var total = 0L
        for (index in 0 until ordered.lastIndex) {
            val current = ordered[index]
            val next = ordered[index + 1]
            if (current.assignedLabelId == labelId) {
                total += (next.positionMs - current.positionMs).coerceIn(0L, MAX_DWELL_GAP_MS)
            }
        }
        return total
    }

    private fun formatDwell(ms: Long): String = "%.2f s dwell".format(ms / 1000.0)

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
            put("experimentId", exp.id)
            put("experimentName", exp.name)
            put("experimentVersion", exp.version)
            put("presentationMode", presentationMode)
            put("startedAtMs", startedAtMs)
            put("completedAtMs", System.currentTimeMillis())
            put("experimentSnapshot", ExperimentStore(context).snapshot(exp))
            put("trials", JSONArray().apply {
                results.forEach { result ->
                    put(JSONObject().apply {
                        put("videoId", result.video.id)
                        put("videoName", result.video.displayName)
                        put("targets", JSONArray(result.video.allTargets().map { it.name }))
                        put("sourceVideoFrames", result.video.sourceFrameCount)
                        put("videoDurationMs", result.video.durationMs)
                        put("presentedDurationMs", result.presentedDurationMs.takeIf { it > 0L } ?: result.video.durationMs)
                        put("endedBySwipe", result.endedBySwipe)
                        put("capturedGazeSamples", result.capturedGazeSamples)
                        put("validSamples", result.validSamples)
                        put("excludedGazeSamples", (result.capturedGazeSamples - result.validSamples).coerceAtLeast(0))
                        put("strictHits", result.strictHits)
                        put("strictHitRate", if (result.validSamples == 0) JSONObject.NULL else result.strictHits.toDouble() / result.validSamples)
                        put("tolerantHits", result.tolerantHits)
                        put("tolerantHitRate", if (result.validSamples == 0) JSONObject.NULL else result.tolerantHits.toDouble() / result.validSamples)
                        put("scoringDelayMs", SCORING_DELAY_MS)
                        put("toleranceExpansionFraction", 0.20)
                        put("meanDistancePx", if (result.validSamples == 0) 0.0 else result.distanceSum / result.validSamples)
                        put("attentionByLabel", JSONArray().apply {
                            result.attentionCounts.entries.sortedByDescending { it.value }.forEach { (labelId, count) ->
                                put(JSONObject().apply {
                                    put("labelId", labelId)
                                    put("labelName", labelName(labelId))
                                    put("samples", count)
                                    put("share", if (result.validSamples == 0) JSONObject.NULL else count.toDouble() / result.validSamples)
                                    put("estimatedDwellMs", dwellMs(result.video.id, labelId))
                                })
                            }
                        })
                    })
                }
            })
        }
        File(directory, "session_summary.json").writeText(summary.toString(2))
        File(directory, "gaze_samples.csv").bufferedWriter().use { writer ->
            writer.appendLine("video_id,video_time_ms,gaze_x_px,gaze_y_px,video_x_normalized,video_y_normalized,strict_hit,tolerant_hit,distance_px,matched_aois,assigned_label_id")
            samples.forEach { sample ->
                writer.appendLine("${sample.videoId},${sample.positionMs},${sample.x},${sample.y},${sample.videoX},${sample.videoY},${sample.strictHit},${sample.tolerantHit},${sample.distancePx},\"${sample.matchedAois}\",${sample.assignedLabelId}")
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
    private fun hitFraction(hit: Int, total: Int): String =
        if (total == 0) "$hit / 0 (--%)" else "$hit / $total (${percent(hit, total)})"
    private fun frameCount(video: ExperimentVideo): String =
        if (video.sourceFrameCount > 0L) video.sourceFrameCount.toString() else "Unavailable"
    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return "%02d:%02d.%03d".format(safe / 60_000, (safe / 1_000) % 60, safe % 1_000)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val SCORING_DELAY_MS = 500L
        private const val OUTSIDE_LABEL_ID = "__OUTSIDE_AOIS__"
        private const val MAX_DWELL_GAP_MS = 250L
    }
}
