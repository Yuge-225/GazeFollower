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
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.gazefollower.mobile.experiment.BodyTarget
import com.gazefollower.mobile.experiment.AoiTrack
import com.gazefollower.mobile.experiment.AspectFitFrameLayout
import com.gazefollower.mobile.experiment.Experiment
import com.gazefollower.mobile.experiment.ExperimentStore
import com.gazefollower.mobile.experiment.ExperimentVideo
import com.gazefollower.mobile.experiment.EditableRoi
import com.gazefollower.mobile.experiment.RoiEditorView
import com.gazefollower.mobile.experiment.RoiKeyframe
import com.gazefollower.mobile.experiment.NormalizedRoi
import kotlin.math.roundToLong

class AuthorExperimentActivity : ComponentActivity() {
    private data class BoxTrack(
        val id: String,
        val keyframes: MutableList<RoiKeyframe>,
        val roiAt: (Long) -> NormalizedRoi?,
    )

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
            video.keyframes.any { frame -> frame.visible && frame.roi != null } &&
                video.additionalAois.all { track -> track.keyframes.any { it.visible && it.roi != null } }
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
                    "Import videos first. Then open each video editor to add and label one or more bounding boxes."
                } else {
                    "$annotatedCount of ${experiment.videos.size} videos ready. Bounding boxes are added and edited inside each video editor."
                }
            ), matchWrap(14))
        }

        experiment.videos.forEachIndexed { index, video ->
            val hasAnnotation = video.keyframes.any { frame -> frame.visible && frame.roi != null }
            val annotationReady = hasAnnotation &&
                video.additionalAois.all { track -> track.keyframes.any { it.visible && it.roi != null } }
            val videoCard = card().apply {
                addView(body("VIDEO ${index + 1} OF ${experiment.videos.size}"), matchWrap(5))
                addView(title(video.displayName, 17f), matchWrap(6))
                addView(body("${formatTime(video.durationMs)} · ${video.width}×${video.height}"), matchWrap(12))
                addView(body("Bounding boxes: ${1 + video.additionalAois.size}"), matchWrap(6))
                addView(annotationBadge(video, annotationReady), matchWrap(8))
                addView(action(
                    if (annotationReady) "Review or edit boxes" else "Open box editor",
                    primary = true,
                ) { showMultiBoxEditor(video) }, matchWrap(8, 54))
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

    private fun showMultiBoxEditor(video: ExperimentVideo) {
        releaseEditorResources()
        var selectedId = PRIMARY_BOX_ID
        var editTimestamp = 0L
        var lastPlaybackPosition = 0L
        var completed = false
        var userSeeking = false
        var preparedPlayer: MediaPlayer? = null

        fun tracks(): List<BoxTrack> = buildList {
            add(BoxTrack(PRIMARY_BOX_ID, video.keyframes, video::roiAt))
            video.additionalAois.forEach { track -> add(BoxTrack(track.id, track.keyframes, track::roiAt)) }
        }

        fun boxNumber(id: String): Int = tracks().indexOfFirst { it.id == id }.coerceAtLeast(0) + 1
        fun defaultBox(index: Int): NormalizedRoi {
            val column = index % 3
            val row = (index / 3) % 4
            val left = 0.08f + column * 0.20f
            val top = 0.12f + row * 0.14f
            return NormalizedRoi(left, top, (left + 0.26f).coerceAtMost(0.96f), (top + 0.22f).coerceAtMost(0.94f))
        }
        fun boxesAt(timestamp: Long): List<EditableRoi> = tracks().mapIndexed { index, binding ->
            EditableRoi(
                id = binding.id,
                label = "Box ${index + 1}",
                roi = binding.roiAt(timestamp),
                color = BOX_COLORS[index % BOX_COLORS.size],
            )
        }
        fun allBoxesInitialized(): Boolean = tracks().all { binding ->
            binding.keyframes.any { it.visible && it.roi != null }
        }

        val videoView = VideoView(this).apply { setVideoPath(video.localPath) }
        editorVideo = videoView
        val roiView = RoiEditorView(this).apply {
            setVideoSize(video.width, video.height)
            setRois(boxesAt(0L), selectedId)
            isEnabled = false
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
            addView(ProgressBar(this@AuthorExperimentActivity).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(40), dp(40)))
            addView(body("Loading frame…").apply { gravity = Gravity.CENTER })
            visibility = View.GONE
        }
        val player = FrameLayout(this).apply {
            setBackgroundColor(0xFF05080D.toInt())
            addView(videoStage, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            addView(loadingOverlay, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val selectedBoxLabel = body("Selected: Box 1 · drag box to move · drag corner to resize").apply {
            gravity = Gravity.CENTER
            setTextColor(BOX_COLORS.first())
        }
        val time = body("00:00.000 / ${formatTime(video.durationMs)}").apply { gravity = Gravity.CENTER }
        val progress = SeekBar(this).apply {
            max = video.durationMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        }
        val status = body(if (video.keyframes.isEmpty()) {
            "Paused · Tap the video to place Box 1"
        } else {
            "Starting replay · all boxes remain visible"
        }).apply { gravity = Gravity.CENTER }
        lateinit var playPauseButton: Button
        lateinit var deleteBoxButton: Button

        fun updateSelection(id: String) {
            selectedId = id
            val index = boxNumber(id) - 1
            selectedBoxLabel.text = "Selected: Box ${index + 1} · drag box to move · drag corner to resize"
            selectedBoxLabel.setTextColor(BOX_COLORS[index % BOX_COLORS.size])
            deleteBoxButton.isEnabled = id != PRIMARY_BOX_ID
        }
        fun refreshBoxes(timestamp: Long) {
            roiView.setRois(boxesAt(timestamp), selectedId)
        }
        fun pauseForEditing(timestampMs: Long = lastPlaybackPosition, seekPlayer: Boolean = false) {
            val timestamp = timestampMs.coerceIn(0L, video.durationMs)
            videoView.pause()
            editTimestamp = timestamp
            lastPlaybackPosition = timestamp
            completed = timestamp >= video.durationMs
            progress.progress = timestamp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            time.text = "${formatTime(timestamp)} / ${formatTime(video.durationMs)}"
            refreshBoxes(timestamp)
            if (seekPlayer) {
                preparedPlayer?.seekTo(timestamp, MediaPlayer.SEEK_CLOSEST) ?: videoView.seekTo(timestamp.toInt())
            }
            roiView.isEnabled = true
            playPauseButton.text = when {
                completed -> "Replay from start"
                allBoxesInitialized() -> "Resume playback"
                else -> "Place missing box"
            }
            status.text = "Paused at ${formatTime(timestamp)} · tap a box to select it, then adjust"
        }
        fun resumePlayback(fromStart: Boolean = false) {
            if (!allBoxesInitialized()) {
                toast("Place every bounding box before playback")
                return
            }
            if (fromStart) {
                videoView.pause()
                videoView.seekTo(1)
                editTimestamp = 0L
                lastPlaybackPosition = 0L
                progress.progress = 0
                refreshBoxes(0L)
            }
            completed = false
            roiView.isEnabled = false
            playPauseButton.text = "Pause & edit boxes"
            status.text = "Playing · all colored boxes follow their saved keyframes"
            if (fromStart) videoView.postDelayed({ videoView.start() }, REPLAY_SEEK_DELAY_MS) else videoView.start()
        }

        roiView.onSelectionChanged = ::updateSelection
        roiView.onMultiRoiCommitted = { id, roi ->
            val binding = tracks().firstOrNull { it.id == id }
            if (binding != null) {
                val timestamp = editTimestamp.coerceIn(0L, video.durationMs)
                binding.keyframes.removeAll { kotlin.math.abs(it.timestampMs - timestamp) <= KEYFRAME_MERGE_MS }
                binding.keyframes += RoiKeyframe(timestamp, true, roi)
                binding.keyframes.sortBy { it.timestampMs }
                markDraftAndSave()
                refreshBoxes(timestamp)
                playPauseButton.isEnabled = allBoxesInitialized()
                status.text = "Box ${boxNumber(id)} saved at ${formatTime(timestamp)}"
            }
        }

        playPauseButton = action(
            if (allBoxesInitialized()) "Pause & edit boxes" else "Place missing box",
            primary = true,
            enabled = allBoxesInitialized(),
        ) {
            when {
                completed -> resumePlayback(fromStart = true)
                videoView.isPlaying -> pauseForEditing()
                else -> resumePlayback()
            }
        }
        val addBoxButton = action("+ Add bounding box", primary = true) {
            videoView.pause()
            val newTrack = AoiTrack(target = video.target)
            val index = tracks().size
            newTrack.keyframes += RoiKeyframe(editTimestamp, true, defaultBox(index))
            video.additionalAois += newTrack
            selectedId = newTrack.id
            markDraftAndSave()
            refreshBoxes(editTimestamp)
            updateSelection(selectedId)
            roiView.isEnabled = true
            playPauseButton.isEnabled = true
            playPauseButton.text = "Resume playback"
            status.text = "Box ${index + 1} added · drag it into position"
        }
        deleteBoxButton = action("Delete selected box", enabled = false) {
            if (selectedId == PRIMARY_BOX_ID) return@action
            val selectedTrack = video.additionalAois.firstOrNull { it.id == selectedId } ?: return@action
            AlertDialog.Builder(this@AuthorExperimentActivity)
                .setTitle("Delete Box ${boxNumber(selectedId)}?")
                .setMessage("All saved positions for this box will be removed.")
                .setPositiveButton("Delete") { _, _ ->
                    video.additionalAois.remove(selectedTrack)
                    selectedId = PRIMARY_BOX_ID
                    markDraftAndSave()
                    refreshBoxes(editTimestamp)
                    updateSelection(selectedId)
                    status.text = "Bounding box deleted"
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userSeeking = true
                videoView.pause()
                roiView.isEnabled = false
                loadingOverlay.visibility = View.VISIBLE
                status.text = "Loading frame…"
            }
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                val timestamp = value.toLong().coerceIn(0L, video.durationMs)
                editTimestamp = timestamp
                lastPlaybackPosition = timestamp
                time.text = "${formatTime(timestamp)} / ${formatTime(video.durationMs)}"
                refreshBoxes(timestamp)
                preparedPlayer?.seekTo(timestamp, MediaPlayer.SEEK_CLOSEST) ?: videoView.seekTo(value)
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                userSeeking = false
                pauseForEditing(seekBar.progress.toLong(), seekPlayer = true)
                loadingOverlay.postDelayed({ if (!userSeeking) loadingOverlay.visibility = View.GONE }, SEEK_LOADING_FALLBACK_MS)
            }
        })

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(16))
            setBackgroundColor(getColor(R.color.background))
            addView(title("Bounding-box editor", 22f), matchWrap(3))
            addView(body("All boxes are shown together in different colors. Tap a box to select it."), matchWrap(6))
            addView(selectedBoxLabel, matchWrap(6))
            addView(addBoxButton, matchWrap(5, 52))
            addView(deleteBoxButton, matchWrap(8, 48))
            addView(time, matchWrap())
            addView(body("Drag the progress bar to inspect and edit any frame"), matchWrap(2))
            addView(progress, matchWrap(6, 40))
            addView(status, matchWrap(10))
            addView(playPauseButton, matchWrap(8, 56))
            addView(action("Done") { showAuthorHome() }, matchWrap(height = 52))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.background))
            addView(player, LinearLayout.LayoutParams(-1, 0, 0.56f))
            addView(ScrollView(this@AuthorExperimentActivity).apply { addView(controls) }, LinearLayout.LayoutParams(-1, 0, 0.44f))
        }
        setContentView(root)

        videoView.setOnPreparedListener {
            preparedPlayer = it
            it.isLooping = false
            it.setOnSeekCompleteListener { if (!userSeeking) loadingOverlay.visibility = View.GONE }
            videoView.seekTo(1)
            if (allBoxesInitialized()) resumePlayback(fromStart = true) else pauseForEditing(0L, seekPlayer = true)
        }
        videoView.setOnCompletionListener { pauseForEditing(video.durationMs) }
        stopEditorTicker()
        editorTicker = object : Runnable {
            override fun run() {
                if (!userSeeking && !completed && videoView.isPlaying) {
                    val position = videoView.currentPosition.coerceAtLeast(0)
                    val timestamp = position.toLong()
                    lastPlaybackPosition = timestamp
                    editTimestamp = timestamp
                    progress.progress = position.coerceAtMost(progress.max)
                    time.text = "${formatTime(timestamp)} / ${formatTime(video.durationMs)}"
                    refreshBoxes(timestamp)
                }
                videoView.postDelayed(this, 33L)
            }
        }.also { videoView.post(it) }
    }

    private fun showEditor(video: ExperimentVideo, track: AoiTrack? = null) {
        releaseEditorResources()
        val target = track?.target ?: video.target
        val keyframes = track?.keyframes ?: video.keyframes
        fun roiAt(timestampMs: Long) = track?.roiAt(timestampMs) ?: video.roiAt(timestampMs)
        val videoView = VideoView(this).apply { setVideoPath(video.localPath) }
        editorVideo = videoView
        val roiView = RoiEditorView(this).apply {
            setVideoSize(video.width, video.height)
            setRoi(roiAt(0L))
            isEnabled = keyframes.isEmpty()
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
            if (keyframes.isEmpty()) {
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
            roiView.setRoi(roiAt(timestamp))
            if (seekPlayer) {
                preparedPlayer?.seekTo(timestamp, MediaPlayer.SEEK_CLOSEST)
                    ?: videoView.seekTo(timestamp.toInt())
            }
            roiView.isEnabled = true
            playPauseButton.text = when {
                completed -> "Replay from start"
                keyframes.isEmpty() -> "Start playback"
                else -> "Resume playback"
            }
            status.text = if (completed) {
                "Final frame · Adjusting the box saves only at ${formatTime(timestamp)}"
            } else {
                "Paused at ${formatTime(timestamp)} · Adjusting the box saves automatically"
            }
        }

        fun resumePlayback(fromStart: Boolean = false) {
            if (keyframes.none { it.visible && it.roi != null }) {
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
                roiView.setRoi(roiAt(0L))
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
            keyframes.removeAll { kotlin.math.abs(it.timestampMs - timestamp) <= KEYFRAME_MERGE_MS }
            keyframes += RoiKeyframe(timestamp, true, roi)
            keyframes.sortBy { it.timestampMs }
            markDraftAndSave()
            playPauseButton.isEnabled = true
            status.text = "Saved at ${formatTime(timestamp)} · This box remains active until you edit again"
        }

        playPauseButton = action(
            if (keyframes.isEmpty()) "Start playback" else "Pause & edit",
            primary = true,
            enabled = keyframes.any { it.visible && it.roi != null },
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
                roiView.setRoi(roiAt(timestamp))
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
            addView(body("CURRENT AOI · ${target.label.uppercase()} · ${video.allTargets().size} TOTAL"), matchWrap(3))
            addView(title("Edit one AOI at a time", 22f), matchWrap(4))
            addView(body("Play the video. When this box is inaccurate, pause, adjust it, and resume. Every adjustment saves automatically."), matchWrap(8))
            addView(action("Switch AOI") {
                val tracks = listOf<AoiTrack?>(null) + video.additionalAois
                val labels = tracks.map { candidate -> candidate?.target?.label ?: video.target.label }
                AlertDialog.Builder(this@AuthorExperimentActivity)
                    .setTitle("Choose an AOI to edit")
                    .setItems(labels.toTypedArray()) { _, index -> showEditor(video, tracks[index]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, matchWrap(5, 48))
            addView(action(
                "+ Add another AOI",
                primary = true,
                enabled = video.allTargets().size < BodyTarget.authorChoices.size,
            ) {
                val choices = BodyTarget.authorChoices.filter { it !in video.allTargets() }
                if (choices.isEmpty()) {
                    toast("All available body regions are already configured")
                } else {
                    AlertDialog.Builder(this@AuthorExperimentActivity)
                        .setTitle("Add an area of interest")
                        .setMessage("Choose the body region for the new independent bounding box.")
                        .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                            val newTrack = AoiTrack(target = choices[index])
                            video.additionalAois += newTrack
                            markDraftAndSave()
                            showEditor(video, newTrack)
                            toast("${newTrack.target.label} AOI added · draw its first box")
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }, matchWrap(5, 52))
            addView(action("Change current AOI type") {
                val choices = BodyTarget.authorChoices.filter { it == target || it !in video.allTargets() }
                AlertDialog.Builder(this@AuthorExperimentActivity)
                    .setTitle("Change ${target.label} AOI")
                    .setItems(choices.map { it.label }.toTypedArray()) { _, index ->
                        val next = choices[index]
                        if (next == target) return@setItems
                        fun applyChange() {
                            if (track == null) video.target = next else track.target = next
                            keyframes.clear()
                            markDraftAndSave()
                            showEditor(video, track)
                        }
                        if (keyframes.isEmpty()) {
                            applyChange()
                        } else {
                            AlertDialog.Builder(this@AuthorExperimentActivity)
                                .setTitle("Clear saved boxes?")
                                .setMessage("Changing the AOI type clears this AOI's saved bounding boxes.")
                                .setPositiveButton("Change and clear") { _, _ -> applyChange() }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }, matchWrap(5, 48))
            if (track != null) {
                addView(action("Delete current AOI") {
                    AlertDialog.Builder(this@AuthorExperimentActivity)
                        .setTitle("Delete ${track.target.label} AOI?")
                        .setMessage("Its saved bounding boxes will be removed from this video.")
                        .setPositiveButton("Delete") { _, _ ->
                            video.additionalAois.remove(track)
                            markDraftAndSave()
                            showEditor(video)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }, matchWrap(8, 48))
            }
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
            addView(player, LinearLayout.LayoutParams(-1, 0, 0.52f))
            addView(
                ScrollView(this@AuthorExperimentActivity).apply { addView(controls) },
                LinearLayout.LayoutParams(-1, 0, 0.48f),
            )
        }
        setContentView(root)
        videoView.setOnPreparedListener {
            preparedPlayer = it
            it.isLooping = false
            it.setOnSeekCompleteListener {
                if (!userSeeking) loadingOverlay.visibility = View.GONE
            }
            videoView.seekTo(1)
            if (keyframes.isEmpty()) {
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
            roiAt = ::roiAt,
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
        roiAt: (Long) -> com.gazefollower.mobile.experiment.NormalizedRoi?,
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
                    if (videoView.isPlaying) roiView.setRoi(roiAt(timestamp))
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
            experiment.videos.any { video -> video.allTargets().any { !it.availableForAuthoring } } ->
                "Choose one of the five current body regions for every AOI"
            experiment.videos.any { video ->
                video.keyframes.none { frame -> frame.visible && frame.roi != null } ||
                    video.additionalAois.any { track -> track.keyframes.none { it.visible && it.roi != null } }
            } ->
                "Every configured AOI needs at least one visible ROI keyframe"
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
            video.sourceFrameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                ?.toLongOrNull() ?: 0L
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
        val labeledCount = (if (video.keyframes.any { it.visible && it.roi != null }) 1 else 0) +
            video.additionalAois.count { track -> track.keyframes.any { it.visible && it.roi != null } }
        val totalCount = 1 + video.additionalAois.size
        text = when {
            ready -> "✓ READY · $labeledCount of $totalCount boxes labeled"
            !video.target.availableForAuthoring -> "! ACTION REQUIRED · Select a new body region"
            else -> "○ NEEDS LABELING · $labeledCount of $totalCount boxes labeled"
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
        private const val PRIMARY_BOX_ID = "primary"
        private val BOX_COLORS = intArrayOf(
            0xFF22C55E.toInt(),
            0xFF38BDF8.toInt(),
            0xFFF97316.toInt(),
            0xFFA855F7.toInt(),
            0xFFF43F5E.toInt(),
            0xFFEAB308.toInt(),
            0xFF14B8A6.toInt(),
            0xFF6366F1.toInt(),
        )
        private const val MAX_VIDEOS = 5
        private const val KEYFRAME_MERGE_MS = 50L
        private const val REPLAY_SEEK_DELAY_MS = 180L
        private const val SEEK_LOADING_FALLBACK_MS = 1_500L
    }
}
