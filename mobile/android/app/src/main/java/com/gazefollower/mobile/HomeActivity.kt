package com.gazefollower.mobile

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import com.gazefollower.mobile.experiment.Experiment
import com.gazefollower.mobile.experiment.ExperimentStore
import com.gazefollower.mobile.experiment.SessionUpload

class HomeActivity : VersionUiActivity() {
    private lateinit var store: ExperimentStore
    private lateinit var summary: TextView
    private lateinit var homeScreen: LinearLayout
    private var choosingVersion = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
        val padding = dp(20)
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            gravity = Gravity.START
        }
        homeScreen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(getColor(R.color.background))
            addView(TextView(this@HomeActivity).apply {
                text = "GazeFollower"
                textSize = 30f
                setTextColor(getColor(R.color.foreground))
            }, matchWrap(bottom = 4))
            addView(TextView(this@HomeActivity).apply {
                text = "Free-viewing gaze experiment"
                textSize = 15f
                setTextColor(getColor(R.color.secondary))
            }, matchWrap(bottom = 28))
            addView(taskCard("Start a Test", "Choose a published version and presentation mode") {
                showPublishedVersions()
            }, matchWrap(bottom = 12))
            addView(taskCard("Manage Versions", "Videos, labels, annotations, and test history") {
                startActivity(Intent(this@HomeActivity, ExperimentVersionsActivity::class.java))
            }, matchWrap(bottom = 12))
            addView(action("Upload Settings") { SessionUpload.settings(this@HomeActivity) }, matchWrap(bottom = 24, height = 48))
            addView(summary, matchWrap())
        }
        setContentView(homeScreen)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (choosingVersion) showHome() else finish()
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val versions = store.list()
        val published = versions.filter { it.status == Experiment.STATUS_PUBLISHED && it.videos.isNotEmpty() }
        summary.text = if (versions.isEmpty()) {
            "No versions yet\nCreate one in Manage Versions"
        } else {
            "${versions.size} versions · ${published.size} ready to test"
        }
        if (choosingVersion) showPublishedVersions()
    }

    private fun showHome() {
        choosingVersion = false
        setContentView(homeScreen)
    }

    private fun showPublishedVersions() {
        val published = store.list().filter { it.status == Experiment.STATUS_PUBLISHED && it.videos.isNotEmpty() }
        choosingVersion = true
        show(page("Choose a version", if (published.isEmpty())
            "Create and publish a version before starting a test." else "Select a published experiment.").apply {
            if (published.isEmpty()) {
                addView(action("Manage Versions", primary = true) {
                    startActivity(Intent(this@HomeActivity, ExperimentVersionsActivity::class.java))
                }, row(12, 56))
            }
            published.forEach { experiment ->
                addView(navCard(experiment.name, "Version ${experiment.version} · ${experiment.videos.size} videos") {
                    startActivity(Intent(this@HomeActivity, VersionDetailActivity::class.java)
                        .putExtra(EXTRA_VERSION_ID, experiment.id))
                }, row(12))
            }
            addView(action("Back") { showHome() }, row(height = 52))
        })
    }

    private fun taskCard(heading: String, description: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(getColor(R.color.card_background))
            setStroke(dp(1), getColor(R.color.card_border))
        }
        addView(TextView(this@HomeActivity).apply {
            text = "$heading                                      ›"
            textSize = 18f
            setTextColor(getColor(R.color.foreground))
        })
        addView(TextView(this@HomeActivity).apply {
            text = description
            textSize = 13f
            setTextColor(getColor(R.color.secondary))
        }, matchWrap())
        isClickable = true
        isFocusable = true
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
