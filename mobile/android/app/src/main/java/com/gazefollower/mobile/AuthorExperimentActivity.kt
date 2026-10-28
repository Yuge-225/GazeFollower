package com.gazefollower.mobile

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.gazefollower.mobile.experiment.BodyTarget
import com.gazefollower.mobile.experiment.AspectFitFrameLayout
import com.gazefollower.mobile.experiment.Experiment
import com.gazefollower.mobile.experiment.ExperimentStore
import com.gazefollower.mobile.experiment.ExperimentVideo
import com.gazefollower.mobile.experiment.RoiEditorView
import com.gazefollower.mobile.experiment.RoiKeyframe
import kotlin.math.roundToLong

class AuthorExperimentActivity : ComponentActivity() {
    private lateinit var store: ExperimentStore
    private lateinit var experiment: Experiment
    private val density by lazy { resources.displayMetrics.density }
    private var editorVideo: VideoView? = null
    private var editorTicker: Runnable? = null

    private val chooseVideos = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        val capacity = (MAX_VIDEOS - experiment.videos.size).coerceAtLeast(0)
        val initialSize = experiment.videos.size
        uris.take(capacity).forEachIndexed { offset, uri ->
            runCatching {
                val choices = BodyTarget.authorChoices
                val target = choices[(initialSize + offset) % choices.size]
                val imported = store.importVideo(uri, target)
                try {
                    readMetadata(imported)
                    require(imported.durationMs > 0 && imported.width > 0 && imported.height > 0) {
                        "The selected file is not a decodable video"
                    }
                    imported
                } catch (error: Exception) {
                    store.deleteVideo(imported)
                    throw error
                }
            }.onSuccess(experiment.videos::add)
                .onFailure { Toast.makeText(this, it.message ?: "Import failed", Toast.LENGTH_LONG).show() }
        }
        markDraftAndSave()
        showAuthorHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
        experiment = store.load()
        showAuthorHome()
    }

    override fun onStop() {
        editorVideo?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        releaseEditorResources()
        super.onDestroy()
    }

    private fun showAuthorHome() {
        releaseEditorResources()
        val annotatedCount = experiment.videos.count { video ->
            video.target.availableForAuthoring && video.keyframes.any { frame -> frame.visible && frame.roi != null }
        }
        val readyToPublish = experiment.videos.isNotEmpty() && annotatedCount == experiment.videos.size
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
            setBackgroundColor(getColor(R.color.background))
            addView(title("Author Experiment", 30f), matchWrap(8))
            addView(body("Build and label the video test before a participant runs it."), matchWrap(8))
            addView(body("${experiment.status.lowercase().replaceFirstChar { it.uppercase() }} · Version ${experiment.version} · Changes save automatically"), matchWrap(22))

            addView(card().apply {
                addView(title("Step 1 · Add videos", 21f), matchWrap(7))
                addView(body("Choose up to five MP4 videos. The app copies them locally, so the phone can run the experiment without the Mac."), matchWrap(14))
                addView(action(
                    if (experiment.videos.isEmpty()) "Choose videos" else "Add more videos (${experiment.videos.size}/$MAX_VIDEOS)",
                    primary = true,
                    enabled = experiment.videos.size < MAX_VIDEOS,
                ) {
                    if (experiment.videos.size >= MAX_VIDEOS) {
                        toast("This version supports up to five videos")
                    } else {
                        chooseVideos.launch(arrayOf("video/*"))
                    }
                }, matchWrap(height = 56))
            }, matchWrap(16))

            addView(title("Step 2 · Label every video", 21f), matchWrap(6))
            addView(body(
                if (experiment.videos.isEmpty()) {
                    "Import videos first. Then choose a target body region and draw its bounding box on the video."
                } else {
                    "$annotatedCount of ${experiment.videos.size} videos annotated. Add keyframes whenever the target box moves or disappears."
                }
            ), matchWrap(14))
        }

        experiment.videos.forEachIndexed { index, video ->
            val hasAnnotation = video.keyframes.any { frame -> frame.visible && frame.roi != null }
            val annotationReady = video.target.availableForAuthoring && hasAnnotation
            val videoCard = card().apply {
                addView(body("VIDEO ${index + 1} OF ${experiment.videos.size}"), matchWrap(5))
                addView(title(video.displayName, 17f), matchWrap(6))
                addView(body("${formatTime(video.durationMs)} · ${video.width}×${video.height}"), matchWrap(12))
                addView(body("1 · Choose the target body region"), matchWrap(4))
                val targetChoices = BodyTarget.authorChoices
                var suppressSelection = false
                val spinner = Spinner(this@AuthorExperimentActivity).apply {
                    adapter = ArrayAdapter(
                        this@AuthorExperimentActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf("Select body region…") + targetChoices.map { it.label },
                    )
                    setSelection(targetChoices.indexOf(video.target).let { if (it < 0) 0 else it + 1 })
                    onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                            if (suppressSelection || position == 0) return
                            val next = targetChoices[position - 1]
                            if (video.target == next) return
                            if (hasAnnotation) {
                                AlertDialog.Builder(this@AuthorExperimentActivity)
                                    .setTitle("Change target region?")
                                    .setMessage("Changing the target makes the existing boxes invalid. The saved annotation for this video will be cleared.")
                                    .setPositiveButton("Change and clear") { _, _ ->
                                        video.target = next
                                        video.keyframes.clear()
                                        markDraftAndSave()
                                        showAuthorHome()
                                    }
                                    .setNegativeButton("Cancel") { _, _ ->
                                        suppressSelection = true
                                        setSelection(targetChoices.indexOf(video.target).let { if (it < 0) 0 else it + 1 })
                                        suppressSelection = false
                                    }
                                    .show()
                            } else {
                                video.target = next
                                markDraftAndSave()
                                showAuthorHome()
                            }
                        }
                    }
                }
                addView(spinner, matchWrap(10, 52))
                addView(body("2 · Draw and review the bounding boxes"), matchWrap(5))
                addView(annotationBadge(video, annotationReady), matchWrap(8))
                addView(action(
                    if (annotationReady) "Review or edit annotation" else "Label this video",
                    primary = true,
                    enabled = video.target.availableForAuthoring,
                ) { showEditor(video) }, matchWrap(8, 54))
                addView(action("Remove video") {
                    store.deleteVideo(video)
                    experiment.videos.remove(video)
                    markDraftAndSave()
                    showAuthorHome()
                }, matchWrap(height = 48))
            }
            content.addView(videoCard, matchWrap(14))
        }

        content.addView(card().apply {
            addView(title("Step 3 · Publish", 21f), matchWrap(7))
            addView(body(when {
                experiment.videos.isEmpty() -> "Waiting for videos."
                readyToPublish -> "All ${experiment.videos.size} videos have a target ROI and are ready to publish."
                else -> "Annotate the remaining ${experiment.videos.size - annotatedCount} video(s) before publishing."
            }), matchWrap(14))
            addView(action("Publish experiment", primary = true, enabled = readyToPublish) { publish() }, matchWrap(height = 56))
        }, matchWrap(16))
        content.addView(action("Back to home") { finish() }, matchWrap(height = 52))
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(getColor(R.color.background))
            addView(content, ViewGroup.LayoutParams(-1, -2))
        })
    }

    private fun showEditor(video: ExperimentVideo) {
        releaseEditorResources()
        val videoView = VideoView(this).apply { setVideoPath(video.localPath) }
        editorVideo = videoView
        val roiView = RoiEditorView(this).apply {
            setVideoSize(video.width, video.height)
            setRoi(video.roiAt(0L))
            isEnabled = video.keyframes.isEmpty()
        }
        val videoStage = AspectFitFrameLayout(this, video.width, video.height).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(videoView, FrameLayout.LayoutParams(-1, -1))
            addView(roiView, FrameLayout.LayoutParams(-1, -1))
        }
        val loadingOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xD9141B26.toInt())
            }
            addView(ProgressBar(this@AuthorExperimentActivity).apply {
                isIndeterminate = true
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(6)
            })
            addView(body("Loading frame…").apply {
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.foreground))
            })
            visibility = View.GONE
        }
        val player = FrameLayout(this).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(
                videoStage,
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
            )
            addView(
                loadingOverlay,
                FrameLayout.LayoutParams(-2, -2, Gravity.CENTER),
            )
        }
        val time = body("00:00.000 / ${formatTime(video.durationMs)}").apply { gravity = Gravity.CENTER }
        val progress = SeekBar(this).apply {
            max = video.durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
        val status = body(
            if (video.keyframes.isEmpty()) {
                "Paused · Tap the target and adjust the first box"
            } else {
                "Starting playback · Current box remains valid until you change it"
            }
        ).apply { gravity = Gravity.CENTER }
        var completed = false
        var editTimestamp = 0L
        var lastPlaybackPosition = 0L
        var userSeeking = false
        var preparedPlayer: MediaPlayer? = null
        lateinit var playPauseButton: Button

        fun pauseForEditing(timestampMs: Long = lastPlaybackPosition, seekPlayer: Boolean = false) {
            val timestamp = timestampMs.coerceIn(0L, video.durationMs)
            videoView.pause()
            editTimestamp = timestamp
            lastPlaybackPosition = timestamp
            completed = timestamp >= video.durationMs
            progress.progress = timestamp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            time.text = "${formatTime(timestamp)} / ${formatTime(video.durationMs)}"
            roiView.setRoi(video.roiAt(timestamp))
            if (seekPlayer) {
                preparedPlayer?.seekTo(timestamp, MediaPlayer.SEEK_CLOSEST)
                    ?: videoView.seekTo(timestamp.toInt())
            }
            roiView.isEnabled = true
            playPauseButton.text = when {
                completed -> "Replay from start"
                video.keyframes.isEmpty() -> "Start playback"
                else -> "Resume playback"
            }
            status.text = if (completed) {
                "Final frame · Adjusting the box saves only at ${formatTime(timestamp)}"
            } else {
                "Paused at ${formatTime(timestamp)} · Adjusting the box saves automatically"
            }
        }

        fun resumePlayback(fromStart: Boolean = false) {
            if (video.keyframes.none { it.visible && it.roi != null }) {
                toast("Set the first bounding box before playback")
                return
            }
            if (fromStart) {
                videoView.pause()
                videoView.seekTo(1)
                editTimestamp = 0L
                lastPlaybackPosition = 0L
                progress.progress = 0
                time.text = "00:00.000 / ${formatTime(video.durationMs)}"
                roiView.setRoi(video.roiAt(0L))
            }
            completed = false
            roiView.isEnabled = false
            playPauseButton.text = "Pause & edit"
            status.text = "Playing · The current box stays valid until the next edit"
            if (fromStart) {
                videoView.postDelayed({
                    if (!completed && !roiView.isEnabled) videoView.start()
                }, REPLAY_SEEK_DELAY_MS)
            } else {
                videoView.start()
            }
        }

        roiView.onRoiCommitted = { roi ->
            val timestamp = editTimestamp.coerceIn(0L, video.durationMs)
            video.keyframes.removeAll { kotlin.math.abs(it.timestampMs - timestamp) <= KEYFRAME_MERGE_MS }
            video.keyframes += RoiKeyframe(timestamp, true, roi)
            video.keyframes.sortBy { it.timestampMs }
            markDraftAndSave()
            playPauseButton.isEnabled = true
            status.text = "Saved at ${formatTime(timestamp)} · This box remains active until you edit again"
        }

        playPauseButton = action(
            if (video.keyframes.isEmpty()) "Start playback" else "Pause & edit",
            primary = true,
            enabled = video.keyframes.any { it.visible && it.roi != null },
        ) {
            when {
                completed -> resumePlayback(fromStart = true)
                videoView.isPlaying -> pauseForEditing()
                else -> resumePlayback()
            }
        }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
                videoView.pause()
                roiView.isEnabled = false
                loadingOverlay.visibility = View.VISIBLE
                status.text = "Seeking · Release to edit this frame"
            }

            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val timestamp = value.toLong().coerceIn(0L, video.durationMs)
                editTimestamp = timestamp
                lastPlaybackPosition = timestamp
                time.text = "${formatTime(timestamp)} / ${formatTime(video.durationMs)}"
                roiView.setRoi(video.roiAt(timestamp))
                preparedPlayer?.seekTo(value.toLong(), MediaPlayer.SEEK_CLOSEST)
                    ?: videoView.seekTo(value)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                pauseForEditing(seekBar.progress.toLong(), seekPlayer = true)
                loadingOverlay.postDelayed({
                    if (!userSeeking) loadingOverlay.visibility = View.GONE
                }, SEEK_LOADING_FALLBACK_MS)
            }
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(16))
            setBackgroundColor(getColor(R.color.background))
            addView(title("Annotate while playing · ${video.target.label}", 22f), matchWrap(4))
            addView(body("If the box becomes inaccurate, pause, adjust it, then resume. Changes save automatically."), matchWrap(8))
            addView(time, matchWrap())
            addView(body("Replay progress · Drag to locate a frame, then redraw the box"), matchWrap(2))
            addView(progress, matchWrap(6, 40))
            addView(status, matchWrap(10))
            addView(playPauseButton, matchWrap(8, 56))
            addView(action("Done") { showAuthorHome() }, matchWrap(height = 52))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            addView(player, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(controls, LinearLayout.LayoutParams(-1, -2))
        }
        setContentView(root)
        videoView.setOnPreparedListener {
            preparedPlayer = it
            it.isLooping = false
            it.setOnSeekCompleteListener {
                if (!userSeeking) loadingOverlay.visibility = View.GONE
            }
            videoView.seekTo(1)
            if (video.keyframes.isEmpty()) {
                pauseForEditing(0L, seekPlayer = true)
            } else {
                resumePlayback(fromStart = true)
            }
        }
        videoView.setOnCompletionListener {
            pauseForEditing(video.durationMs, seekPlayer = false)
        }
        startPlaybackTicker(
            videoView = videoView,
            time = time,
            roiView = roiView,
            progress = progress,
            video = video,
            shouldUpdate = { !userSeeking && !completed },
            onPosition = { lastPlaybackPosition = it },
        )
    }

    private fun startPlaybackTicker(
        videoView: VideoView,
        time: TextView,
        roiView: RoiEditorView,
        progress: SeekBar,
        video: ExperimentVideo,
        shouldUpdate: () -> Boolean,
        onPosition: (Long) -> Unit,
    ) {
        stopEditorTicker()
        editorTicker = object : Runnable {
            override fun run() {
                if (shouldUpdate()) {
                    val position = videoView.currentPosition.coerceAtLeast(0)
                    val timestamp = position.toLong()
                    onPosition(timestamp)
                    progress.progress = position.coerceAtMost(progress.max)
                    time.text = "${formatTime(timestamp)} / ${formatTime(video.durationMs)}"
                    if (videoView.isPlaying) roiView.setRoi(video.roiAt(timestamp))
                }
                videoView.postDelayed(this, 33L)
            }
        }.also { videoView.post(it) }
    }

    private fun stopEditorTicker() {
        val ticker = editorTicker
        if (ticker != null) editorVideo?.removeCallbacks(ticker)
        editorTicker = null
    }

    private fun releaseEditorResources() {
        stopEditorTicker()
        editorVideo?.pause()
        editorVideo = null
    }

    private fun publish() {
        val error = when {
            experiment.videos.isEmpty() -> "Import at least one video"
            experiment.videos.any { !it.target.availableForAuthoring } ->
                "Choose one of the five current body regions for every video"
            experiment.videos.any { it.keyframes.none { frame -> frame.visible && frame.roi != null } } ->
                "Every video needs at least one visible ROI keyframe"
            else -> null
        }
        if (error != null) {
            toast(error)
            return
        }
        experiment.version += 1
        experiment.status = Experiment.STATUS_PUBLISHED
        store.save(experiment)
        toast("Experiment published as version ${experiment.version}")
        showAuthorHome()
    }

    private fun markDraftAndSave() {
        experiment.status = Experiment.STATUS_DRAFT
        store.save(experiment)
    }

    private fun readMetadata(video: ExperimentVideo) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.localPath)
            video.durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                video.width = rawHeight
                video.height = rawWidth
            } else {
                video.width = rawWidth
                video.height = rawHeight
            }
        } finally {
            retriever.release()
        }
    }

    private fun title(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.foreground))
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(getColor(R.color.secondary))
    }

    private fun action(
        label: String,
        primary: Boolean = false,
        enabled: Boolean = true,
        click: () -> Unit,
    ) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        isEnabled = enabled
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(getColor(R.color.button_text), getColor(R.color.button_text_disabled)),
        ))
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(
                getColor(if (primary) R.color.button_background else R.color.button_secondary_background),
                getColor(R.color.button_background_disabled),
            ),
        )
        setOnClickListener { click() }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(getColor(R.color.card_background))
            setStroke(dp(1), getColor(R.color.card_border))
        }
    }

    private fun annotationBadge(video: ExperimentVideo, ready: Boolean) = TextView(this).apply {
        text = when {
            ready -> "✓ LABELED · ${video.target.label} · ${video.keyframes.size} saved box changes"
            !video.target.availableForAuthoring -> "! ACTION REQUIRED · Select a new body region"
            else -> "○ NOT LABELED · Target: ${video.target.label}"
        }
        textSize = 14f
        setTextColor(if (ready) 0xFFB8F5CD.toInt() else 0xFFFFD3A1.toInt())
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(if (ready) 0xFF183A2A.toInt() else 0xFF3A2C1D.toInt())
            setStroke(dp(1), if (ready) 0xFF2D8150.toInt() else 0xFF9A642B.toInt())
        }
    }

    private fun matchWrap(bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
        val resolvedHeight = if (height == ViewGroup.LayoutParams.WRAP_CONTENT) height else dp(height)
        return LinearLayout.LayoutParams(-1, resolvedHeight).apply { bottomMargin = dp(bottom) }
    }

    private fun dp(value: Int) = (value * density).roundToLong().toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun formatTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        return "%02d:%02d.%03d".format(safe / 60_000, (safe / 1_000) % 60, safe % 1_000)
    }

    companion object {
        private const val MAX_VIDEOS = 5
        private const val KEYFRAME_MERGE_MS = 50L
        private const val REPLAY_SEEK_DELAY_MS = 180L
        private const val SEEK_LOADING_FALLBACK_MS = 1_500L
    }
}
