package com.gazefollower.mobile.experiment

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class BodyTarget(val label: String, val availableForAuthoring: Boolean = true) {
    HEAD("Head"),
    CHEST("Chest"),
    ABDOMEN("Abdomen"),
    LEG("Leg"),
    FOOT("Foot"),

    // Kept only so manifests created by earlier builds remain readable.
    LEFT_ARM("Left arm (legacy)", false),
    RIGHT_ARM("Right arm (legacy)", false),
    LEGS("Legs (legacy)", false);

    companion object {
        val authorChoices = entries.filter { it.availableForAuthoring }
    }
}

data class NormalizedRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun expanded(fraction: Float): NormalizedRoi {
        val dx = (right - left) * fraction / 2f
        val dy = (bottom - top) * fraction / 2f
        return NormalizedRoi(
            (left - dx).coerceAtLeast(0f),
            (top - dy).coerceAtLeast(0f),
            (right + dx).coerceAtMost(1f),
            (bottom + dy).coerceAtMost(1f),
        )
    }
}

data class RoiKeyframe(
    val timestampMs: Long,
    val visible: Boolean,
    val roi: NormalizedRoi?,
)

data class ExperimentVideo(
    val id: String,
    var displayName: String,
    val localPath: String,
    var durationMs: Long,
    var width: Int,
    var height: Int,
    var target: BodyTarget,
    val keyframes: MutableList<RoiKeyframe> = mutableListOf(),
) {
    fun roiAt(timestampMs: Long): NormalizedRoi? {
        val ordered = keyframes.sortedBy { it.timestampMs }
        if (ordered.isEmpty()) return null
        val active = ordered.lastOrNull { it.timestampMs <= timestampMs } ?: ordered.first()
        return if (active.visible) active.roi else null
    }
}

data class Experiment(
    var name: String = "Five-region video experiment",
    var status: String = STATUS_DRAFT,
    var version: Int = 0,
    val videos: MutableList<ExperimentVideo> = mutableListOf(),
) {
    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_PUBLISHED = "PUBLISHED"
    }
}

class ExperimentStore(private val context: Context) {
    private val root = File(context.filesDir, "experiments/current")
    private val videoDirectory = File(root, "videos")
    private val manifestFile = File(root, "manifest.json")

    init {
        videoDirectory.mkdirs()
    }

    @Synchronized
    fun load(): Experiment {
        if (!manifestFile.exists()) return Experiment()
        return runCatching { decode(JSONObject(manifestFile.readText())) }.getOrElse { Experiment() }
    }

    @Synchronized
    fun save(experiment: Experiment) {
        root.mkdirs()
        manifestFile.writeText(encode(experiment).toString(2))
    }

    fun importVideo(uri: Uri, target: BodyTarget): ExperimentVideo {
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) cursor.getString(index) else null
        } ?: "video.mp4"
        val extension = name.substringAfterLast('.', "mp4").take(8)
        val id = UUID.randomUUID().toString()
        val destination = File(videoDirectory, "$id.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected video" }
            destination.outputStream().use(input::copyTo)
        }
        return ExperimentVideo(
            id = id,
            displayName = name,
            localPath = destination.absolutePath,
            durationMs = 0L,
            width = 0,
            height = 0,
            target = target,
        )
    }

    fun deleteVideo(video: ExperimentVideo) {
        File(video.localPath).delete()
    }

    private fun encode(experiment: Experiment): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("name", experiment.name)
        put("status", experiment.status)
        put("version", experiment.version)
        put("videos", JSONArray().apply {
            experiment.videos.forEach { video ->
                put(JSONObject().apply {
                    put("id", video.id)
                    put("displayName", video.displayName)
                    put("localPath", video.localPath)
                    put("durationMs", video.durationMs)
                    put("width", video.width)
                    put("height", video.height)
                    put("target", video.target.name)
                    put("keyframes", JSONArray().apply {
                        video.keyframes.sortedBy { it.timestampMs }.forEach { keyframe ->
                            put(JSONObject().apply {
                                put("timestampMs", keyframe.timestampMs)
                                put("visible", keyframe.visible)
                                keyframe.roi?.let { roi ->
                                    put("roi", JSONObject().apply {
                                        put("left", roi.left.toDouble())
                                        put("top", roi.top.toDouble())
                                        put("right", roi.right.toDouble())
                                        put("bottom", roi.bottom.toDouble())
                                    })
                                }
                            })
                        }
                    })
                })
            }
        })
    }

    private fun decode(json: JSONObject): Experiment = Experiment(
        name = json.optString("name", "Five-region video experiment"),
        status = json.optString("status", Experiment.STATUS_DRAFT),
        version = json.optInt("version", 0),
        videos = mutableListOf<ExperimentVideo>().apply {
            val videos = json.optJSONArray("videos") ?: JSONArray()
            for (index in 0 until videos.length()) {
                val item = videos.getJSONObject(index)
                add(ExperimentVideo(
                    id = item.getString("id"),
                    displayName = item.getString("displayName"),
                    localPath = item.getString("localPath"),
                    durationMs = item.optLong("durationMs"),
                    width = item.optInt("width"),
                    height = item.optInt("height"),
                    target = runCatching { BodyTarget.valueOf(item.getString("target")) }
                        .getOrDefault(BodyTarget.HEAD),
                    keyframes = mutableListOf<RoiKeyframe>().apply {
                        val frames = item.optJSONArray("keyframes") ?: JSONArray()
                        for (frameIndex in 0 until frames.length()) {
                            val frame = frames.getJSONObject(frameIndex)
                            val roiJson = frame.optJSONObject("roi")
                            add(RoiKeyframe(
                                timestampMs = frame.getLong("timestampMs"),
                                visible = frame.optBoolean("visible", true),
                                roi = roiJson?.let {
                                    NormalizedRoi(
                                        it.getDouble("left").toFloat(),
                                        it.getDouble("top").toFloat(),
                                        it.getDouble("right").toFloat(),
                                        it.getDouble("bottom").toFloat(),
                                    )
                                },
                            ))
                        }
                    },
                ))
            }
        },
    )
}
