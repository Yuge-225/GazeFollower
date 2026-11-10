package com.gazefollower.mobile

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gazefollower.mobile.experiment.Experiment
import com.gazefollower.mobile.experiment.ExperimentStore
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class ExperimentVersionsActivity : VersionUiActivity() {
    private lateinit var store: ExperimentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val versions = store.list()
        val content = page("Versions", "${versions.size} active versions")
        content.addView(action("+ New Version", primary = true) {
            showCreateDialog()
        }, row(14, 48))
        if (versions.isEmpty()) {
            content.addView(body("No versions yet. Create one to add stimulus videos and bounding boxes."), row(16))
        }
        versions.forEach { experiment ->
            val annotated = experiment.videos.count { video ->
                video.keyframes.any { it.visible && it.roi != null } &&
                    video.additionalAois.all { track -> track.keyframes.any { it.visible && it.roi != null } }
            }
            content.addView(card().apply {
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@ExperimentVersionsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title("V${experiment.version}  ${experiment.name}", 17f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(action("⋮") { showOptions(experiment) }, LinearLayout.LayoutParams(dp(44), dp(40)))
                }, row(4))
                addView(body("${experiment.status} · ${experiment.videos.size} videos · $annotated ready · ${store.runCount(experiment.id)} tests"))
                setOnClickListener { openVersion(experiment) }
            }, row(10))
        }
        content.addView(action("Back") { finish() }, row(height = 46))
        show(content)
    }

    private fun openVersion(experiment: Experiment) {
        startActivity(Intent(this, VersionDetailActivity::class.java).putExtra(EXTRA_VERSION_ID, experiment.id))
    }

    private fun showOptions(experiment: Experiment) {
        val items = arrayOf("Rename", "Make a Copy", "Archive", "Delete")
        AlertDialog.Builder(this)
            .setTitle("Version ${experiment.version}")
            .setItems(items) { _, index ->
                when (index) {
                    0 -> showRenameDialog(experiment)
                    1 -> openVersion(store.duplicate(experiment))
                    2 -> {
                        store.archive(experiment)
                        render()
                    }
                    3 -> confirmDelete(experiment)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateDialog() {
        val input = EditText(this).apply {
            hint = "Version name"
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Create New Version")
            .setMessage("Give this version a short name. You can change it later.")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim().ifBlank { "New experiment version" }
                openVersion(store.createNew(name))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(experiment: Experiment) {
        val input = EditText(this).apply {
            setText(experiment.name)
            selectAll()
            setSingleLine(true)
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Version ${experiment.version}")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                input.text.toString().trim().takeIf { it.isNotBlank() }?.let { experiment.name = it }
                store.save(experiment)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete(experiment: Experiment) {
        val hasRuns = store.hasRuns(experiment.id)
        AlertDialog.Builder(this)
            .setTitle(if (hasRuns) "Archive Version ${experiment.version}?" else "Delete Version ${experiment.version}?")
            .setMessage(if (hasRuns) {
                "This version has test results, so it will be archived rather than permanently deleted."
            } else {
                "This removes the version configuration. This action cannot be undone."
            })
            .setPositiveButton(if (hasRuns) "Archive" else "Delete") { _, _ ->
                if (hasRuns) store.archive(experiment) else store.delete(experiment)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class VersionDetailActivity : VersionUiActivity() {
    private lateinit var store: ExperimentStore
    private lateinit var experiment: Experiment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra(EXTRA_VERSION_ID)
        experiment = store.load(id)
        render()
    }

    private fun render() {
        val runs = store.runCount(experiment.id)
        val boxes = experiment.videos.sumOf { 1 + it.additionalAois.size }
        val content = page(
            "Version ${experiment.version}",
            "${experiment.name}\n${experiment.status} · ${experiment.videos.size} videos · $boxes boxes · $runs tests",
        )
        content.addView(navCard("Edit Setup", "Videos and labeled AOIs") { requestEdit() }, row(10))
        content.addView(navCard("Start a New Test", "Free viewing · Ready or Scroll") { chooseMode() }, row(10))
        content.addView(navCard("Test History", "$runs completed tests") {
            startActivity(Intent(this, TestHistoryActivity::class.java).putExtra(EXTRA_VERSION_ID, experiment.id))
        }, row(14))
        content.addView(action("Back") { finish() }, row(height = 46))
        show(content)
    }

    private fun requestEdit() {
        if (!store.hasRuns(experiment.id)) return openEditor(experiment)
        val nextVersion = (store.list(includeArchived = true).maxOfOrNull { it.version } ?: experiment.version) + 1
        AlertDialog.Builder(this)
            .setTitle("Edit Version ${experiment.version}?")
            .setMessage(
                "This version already has test results.\n\n" +
                    "Changing its videos or annotations may make earlier results inconsistent with the current setup.\n\n" +
                    "What would you like to do?",
            )
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Edit Version ${experiment.version}") { _, _ -> openEditor(experiment) }
            .setPositiveButton("Create Version $nextVersion") { _, _ -> openEditor(store.duplicate(experiment)) }
            .show()
    }

    private fun openEditor(value: Experiment) {
        startActivity(Intent(this, AuthorExperimentActivity::class.java).putExtra(EXTRA_VERSION_ID, value.id))
    }

    private fun chooseMode() {
        if (experiment.status != Experiment.STATUS_PUBLISHED || experiment.videos.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Version not ready")
                .setMessage("Finish the video annotations and publish this version before starting a test.")
                .setPositiveButton("Edit Setup") { _, _ -> requestEdit() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Free-viewing presentation mode")
            .setItems(arrayOf(
                "Ready Mode\nWatch naturally; press Ready before every video",
                "Scroll Mode\nWatch naturally; swipe up for the next video",
            )) { _, index ->
                startActivity(Intent(this, MainActivity::class.java)
                    .putExtra(EXTRA_VERSION_ID, experiment.id)
                    .putExtra(EXTRA_PRESENTATION_MODE, if (index == 0) MODE_READY else MODE_SCROLL))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

class TestHistoryActivity : VersionUiActivity() {
    private lateinit var store: ExperimentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
        val experiment = store.load(intent.getStringExtra(EXTRA_VERSION_ID))
        val runs = store.sessionSummaries(experiment.id)
        val content = page("Test History", "Version ${experiment.version} · ${runs.size} completed tests")
        if (runs.isEmpty()) content.addView(body("No completed tests for this version."), row(18))
        runs.forEachIndexed { index, run ->
            val trials = run.optJSONArray("trials")
            val valid = (0 until (trials?.length() ?: 0)).sumOf { trials!!.getJSONObject(it).optInt("validSamples") }
            val mode = run.optString("presentationMode", MODE_READY).displayMode()
            val number = runs.size - index
            content.addView(card().apply {
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@TestHistoryActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title("Test ${number.toString().padStart(3, '0')}", 17f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(title("›", 21f), LinearLayout.LayoutParams(-2, -2))
                }, row(3))
                addView(body("${mode.uppercase()} · ${formatDate(run.optLong("completedAtMs", run.optLong("startedAtMs")))}"), row(3))
                addView(body("${trials?.length() ?: 0} videos · $valid valid samples"))
                setOnClickListener {
                    startActivity(Intent(this@TestHistoryActivity, RunResultActivity::class.java)
                        .putExtra(EXTRA_VERSION_ID, experiment.id)
                        .putExtra(EXTRA_SESSION_ID, run.optString("sessionId")))
                }
            }, row(9))
        }
        content.addView(action("Back") { finish() }, row(height = 46))
        show(content)
    }
}

class RunResultActivity : VersionUiActivity() {
    private lateinit var store: ExperimentStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
        render()
    }

    private fun render() {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val file = store.sessionDirectory(sessionId).resolve("session_summary.json")
        if (!file.exists()) {
            show(page("Test Result", "The saved result file is unavailable."))
            return
        }
        val run = JSONObject(file.readText())
        val experiment = run.optJSONObject("experimentSnapshot")
            ?.let(store::fromSnapshot)
            ?: store.load(intent.getStringExtra(EXTRA_VERSION_ID))
        val trials = run.optJSONArray("trials")
        val trialObjects = (0 until (trials?.length() ?: 0)).map { trials!!.getJSONObject(it) }
        val valid = trialObjects.sumOf { it.optInt("validSamples") }
        val strict = trialObjects.sumOf { it.optInt("strictHits") }
        val tolerant = trialObjects.sumOf { it.optInt("tolerantHits") }
        val mode = run.optString("presentationMode", MODE_READY).displayMode()
        val content = page("Test Result · $mode", formatDate(run.optLong("completedAtMs", run.optLong("startedAtMs"))))
        val aggregateAttention = linkedMapOf<String, Int>()
        trialObjects.forEach { trial ->
            val attention = trial.optJSONArray("attentionByLabel") ?: return@forEach
            for (index in 0 until attention.length()) {
                val item = attention.getJSONObject(index)
                val name = item.optString("labelName", "Unknown")
                aggregateAttention[name] = (aggregateAttention[name] ?: 0) + item.optInt("samples")
            }
        }
        content.addView(title("Attention", 20f), row(8))
        if (aggregateAttention.isEmpty()) {
            content.addView(body("Attention distribution was not recorded for this earlier test."), row(12))
        } else {
            aggregateAttention.entries.sortedByDescending { it.value }.forEach { (name, count) ->
                content.addView(attentionRow(name, count, valid), row(7))
            }
        }
        val technical = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(body("Valid samples · $valid"), row(4))
            addView(body("Inside exact body AOIs · ${fraction(strict, valid)}"), row(4))
            addView(body("Inside tolerant AOIs · ${fraction(tolerant, valid)}"), row(8))
        }
        val technicalToggle = action("Show Technical Details") {}
        technicalToggle.setOnClickListener {
            val show = technical.visibility != View.VISIBLE
            technical.visibility = if (show) View.VISIBLE else View.GONE
            technicalToggle.text = if (show) "Hide Technical Details" else "Show Technical Details"
        }
        content.addView(technicalToggle, row(6, 44))
        content.addView(technical, row(14))
        content.addView(title("Videos", 20f), row(8))
        trialObjects.forEachIndexed { index, trial ->
            val savedVideoId = trial.optString("videoId")
            val configuredVideo = experiment.videos.firstOrNull { it.id == savedVideoId }
                ?: experiment.videos.getOrNull(index)
            val duration = trial.optLong("videoDurationMs", configuredVideo?.durationMs ?: 0L)
            val cutoff = trial.optLong("presentedDurationMs", duration).takeIf { it > 0L } ?: duration
            val videoName = trial.optString("videoName").takeIf { it.isNotBlank() }
                ?: configuredVideo?.displayName
                ?: "Video ${index + 1}"
            content.addView(card().apply {
                isClickable = true
                isFocusable = true
                addView(LinearLayout(this@RunResultActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(title("${index + 1}. $videoName", 16f), LinearLayout.LayoutParams(0, -2, 1f))
                    addView(title("›", 22f), LinearLayout.LayoutParams(-2, -2))
                }, row(3))
                addView(body("${mode.uppercase()} · ${formatDuration(cutoff)} / ${formatDuration(duration)}"), row(4))
                if (mode == "Scroll Mode") {
                    addView(body(if (trial.optBoolean("endedBySwipe", false)) {
                        "Swiped at ${formatDuration(cutoff)} · gaze data unavailable afterward"
                    } else {
                        "Video completed before a swipe"
                    }), row(5))
                }
                val attention = trial.optJSONArray("attentionByLabel")
                if (attention != null && attention.length() > 0) {
                    val top = (0 until attention.length()).map { attention.getJSONObject(it) }
                        .sortedByDescending { it.optInt("samples") }.take(2)
                    addView(body(top.joinToString(" · ") { item ->
                        "${item.optString("labelName")} ${percentOnly(item.optInt("samples"), trial.optInt("validSamples"))}"
                    }))
                } else {
                    addView(body("${trial.optInt("validSamples")} valid samples"))
                }
                setOnClickListener {
                    val replay = Intent(this@RunResultActivity, VideoReplayActivity::class.java)
                        .putExtra(EXTRA_VERSION_ID, intent.getStringExtra(EXTRA_VERSION_ID))
                        .putExtra(EXTRA_SESSION_ID, sessionId)
                        .putExtra(EXTRA_VIDEO_ID, configuredVideo?.id ?: savedVideoId)
                        .putExtra(EXTRA_TRIAL_INDEX, index)
                    startActivity(replay)
                }
            }, row(9))
        }
        content.addView(action("Back") { finish() }, row(height = 46))
        show(content)
    }
}

abstract class VersionUiActivity : ComponentActivity() {
    protected val density by lazy { resources.displayMetrics.density }

    protected fun page(heading: String, subheading: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(18), dp(20), dp(24))
        setBackgroundColor(getColor(R.color.background))
        addView(title(heading, 26f), row(4))
        addView(body(subheading), row(16))
    }

    protected fun show(content: LinearLayout) = setContentView(ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(getColor(R.color.background))
        addView(content, ViewGroup.LayoutParams(-1, -2))
    })

    protected fun title(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(getColor(R.color.foreground))
    }

    protected fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(getColor(R.color.secondary))
    }

    protected fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(getColor(R.color.card_background))
            setStroke(dp(1), getColor(R.color.card_border))
        }
    }

    protected fun navCard(heading: String, description: String, click: () -> Unit) = card().apply {
        isClickable = true
        isFocusable = true
        addView(LinearLayout(this@VersionUiActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title(heading, 17f), LinearLayout.LayoutParams(0, -2, 1f))
            addView(title("›", 21f), LinearLayout.LayoutParams(-2, -2))
        }, row(3))
        addView(body(description))
        setOnClickListener { click() }
    }

    protected fun action(label: String, primary: Boolean = false, click: () -> Unit) = Button(this).apply {
        text = label
        textSize = 14f
        isAllCaps = false
        setTextColor(getColor(R.color.button_text))
        backgroundTintList = ColorStateList.valueOf(getColor(if (primary) R.color.button_background else R.color.button_secondary_background))
        setOnClickListener { click() }
    }

    protected fun attentionRow(name: String, count: Int, total: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(this@VersionUiActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(body(name).apply { setTextColor(getColor(R.color.foreground)) }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(body("${percentOnly(count, total)} · $count"), LinearLayout.LayoutParams(-2, -2))
        })
        addView(ProgressBar(this@VersionUiActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (total == 0) 0 else (count * 1000 / total)
            progressTintList = ColorStateList.valueOf(getColor(R.color.button_background))
            progressBackgroundTintList = ColorStateList.valueOf(getColor(R.color.card_border))
        }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(3) })
    }

    protected fun row(bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(-1, if (height < 0) height else dp(height)).apply { bottomMargin = dp(bottom) }

    protected fun dp(value: Int) = (value * density).toInt()
    protected fun formatDate(timestamp: Long): String = if (timestamp <= 0L) "Date unavailable" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))
    protected fun fraction(hit: Int, total: Int) = if (total == 0) "$hit / 0 (--%)" else "$hit / $total (${"%.1f".format(hit * 100.0 / total)}%)"
    protected fun percentOnly(hit: Int, total: Int) = if (total == 0) "--%" else "%.1f%%".format(hit * 100.0 / total)
    protected fun formatDuration(ms: Long) = "%.1f s".format(ms.coerceAtLeast(0L) / 1000.0)
}

const val EXTRA_VERSION_ID = "experiment_version_id"
const val EXTRA_PRESENTATION_MODE = "presentation_mode"
const val EXTRA_SESSION_ID = "session_id"
const val EXTRA_VIDEO_ID = "video_id"
const val EXTRA_TRIAL_INDEX = "trial_index"
const val MODE_READY = "READY"
const val MODE_SCROLL = "SCROLL"
private fun String.displayMode() = if (this == MODE_SCROLL) "Scroll Mode" else "Ready Mode"
