package com.gazefollower.mobile

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.gazefollower.mobile.experiment.Experiment
import com.gazefollower.mobile.experiment.ExperimentStore

class HomeActivity : ComponentActivity() {
    private lateinit var store: ExperimentStore
    private lateinit var summary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = ExperimentStore(this)
        val padding = dp(20)
        summary = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.secondary))
            gravity = Gravity.START
        }
        val root = LinearLayout(this).apply {
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
            }, matchWrap(bottom = 24))
            addView(summary, matchWrap())
        }
        setContentView(root)
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
    }

    private fun showPublishedVersions() {
        val published = store.list().filter { it.status == Experiment.STATUS_PUBLISHED && it.videos.isNotEmpty() }
        if (published.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("No version is ready")
                .setMessage("Create and publish a version before starting a test.")
                .setPositiveButton("Manage Versions") { _, _ ->
                    startActivity(Intent(this, ExperimentVersionsActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Choose a version")
            .setItems(published.map { "Version ${it.version} · ${it.name}" }.toTypedArray()) { _, index ->
                startActivity(Intent(this, VersionDetailActivity::class.java).putExtra(EXTRA_VERSION_ID, published[index].id))
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
