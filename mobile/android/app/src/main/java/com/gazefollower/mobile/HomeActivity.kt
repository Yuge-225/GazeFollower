package com.gazefollower.mobile

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gazefollower.mobile.experiment.Experiment
import com.gazefollower.mobile.experiment.ExperimentStore

class HomeActivity : ComponentActivity() {
    private lateinit var store: ExperimentStore
    private lateinit var summary: TextView
    private lateinit var runButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
        val padding = (24 * resources.displayMetrics.density).toInt()
        summary = TextView(this).apply {
            textSize = 16f
            setTextColor(getColor(R.color.secondary))
            gravity = Gravity.CENTER
        }
        runButton = actionButton("Run Experiment") {
            startActivity(Intent(this, MainActivity::class.java))
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.background))
            addView(TextView(this@HomeActivity).apply {
                text = "GazeFollower"
                textSize = 36f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.foreground))
            }, matchWrap(bottom = 12))
            addView(TextView(this@HomeActivity).apply {
                text = "Mobile gaze experiment"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(getColor(R.color.secondary))
            }, matchWrap(bottom = 36))
            addView(actionButton("Author Experiment") {
                startActivity(Intent(this@HomeActivity, AuthorExperimentActivity::class.java))
            }, matchWrap(bottom = 16, height = 64))
            addView(runButton, matchWrap(bottom = 28, height = 64))
            addView(summary, matchWrap())
        }
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        val experiment = store.load()
        val published = experiment.status == Experiment.STATUS_PUBLISHED && experiment.videos.isNotEmpty()
        runButton.isEnabled = published
        runButton.alpha = if (published) 1f else 0.45f
        summary.text = if (published) {
            "${experiment.name}\nPublished v${experiment.version} · ${experiment.videos.size} videos"
        } else if (experiment.videos.isEmpty()) {
            "No published experiment\nCreate one in Author Experiment"
        } else {
            "Draft · ${experiment.videos.size} videos\nPublish it before running"
        }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setTextColor(ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(
                getColor(R.color.button_text),
                getColor(R.color.button_text_disabled)
            )
        ))
        backgroundTintList = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(
                getColor(R.color.button_background),
                getColor(R.color.button_background_disabled)
            )
        )
        setOnClickListener { onClick() }
    }

    private fun matchWrap(bottom: Int = 0, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): LinearLayout.LayoutParams {
        val resolvedHeight = if (height == ViewGroup.LayoutParams.WRAP_CONTENT) {
            height
        } else {
            (height * resources.displayMetrics.density).toInt()
        }
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resolvedHeight).apply {
            bottomMargin = (bottom * resources.displayMetrics.density).toInt()
        }
    }
}
