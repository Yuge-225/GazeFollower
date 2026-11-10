package com.gazefollower.mobile.experiment

import android.content.Context
import android.net.Uri
import android.media.MediaMetadataRetriever
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

data class AoiLabel(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var colorArgb: Int,
    var archived: Boolean = false,
)

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

data class AoiTrack(
    val id: String = UUID.randomUUID().toString(),
    var target: BodyTarget,
    var labelId: String = target.name,
    val keyframes: MutableList<RoiKeyframe> = mutableListOf(),
) {
    fun roiAt(timestampMs: Long): NormalizedRoi? {
        val ordered = keyframes.sortedBy { it.timestampMs }
        if (ordered.isEmpty()) return null
        val active = ordered.lastOrNull { it.timestampMs <= timestampMs } ?: ordered.first()
        return if (active.visible) active.roi else null
    }
}

data class ExperimentVideo(
    val id: String,
    var displayName: String,
    val localPath: String,
    var durationMs: Long,
    var width: Int,
    var height: Int,
    var sourceFrameCount: Long = 0L,
    var target: BodyTarget,
    var labelId: String = target.name,
    val keyframes: MutableList<RoiKeyframe> = mutableListOf(),
    val additionalAois: MutableList<AoiTrack> = mutableListOf(),
) {
    fun roiAt(timestampMs: Long): NormalizedRoi? {
        val ordered = keyframes.sortedBy { it.timestampMs }
        if (ordered.isEmpty()) return null
        val active = ordered.lastOrNull { it.timestampMs <= timestampMs } ?: ordered.first()
        return if (active.visible) active.roi else null
    }


    fun activeAoisAt(timestampMs: Long): List<Pair<String, NormalizedRoi>> = buildList {
        roiAt(timestampMs)?.let { add(labelId to it) }
        additionalAois.forEach { track ->
            track.roiAt(timestampMs)?.let { add(track.labelId to it) }
        }
    }

    fun allTargets(): List<BodyTarget> = buildList {
        add(target)
        addAll(additionalAois.map { it.target })
    }.distinct()
}

data class Experiment(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Five-region video experiment",
    var status: String = STATUS_DRAFT,
    var version: Int = 1,
    var createdAtMs: Long = System.currentTimeMillis(),
    var updatedAtMs: Long = System.currentTimeMillis(),
    var archived: Boolean = false,
    val labels: MutableList<AoiLabel> = mutableListOf(),
    val videos: MutableList<ExperimentVideo> = mutableListOf(),
) {
    companion object {
        const val STATUS_DRAFT = "DRAFT"
        const val STATUS_PUBLISHED = "PUBLISHED"
    }
}

class LabelLibraryStore(private val context: Context) {
    private val file = File(context.filesDir, "label_library.json")

    @Synchronized
    fun load(): MutableList<AoiLabel> {
        if (!file.exists()) return defaultLabels().also(::save)
        return runCatching {
            val array = JSONArray(file.readText())
            mutableListOf<AoiLabel>().apply {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(AoiLabel(
                        id = item.getString("id"),
                        name = item.getString("name"),
                        colorArgb = item.optLong("colorArgb", DEFAULT_COLORS[index % DEFAULT_COLORS.size].toLong()).toInt(),
                        archived = item.optBoolean("archived", false),
                    ))
                }
            }
        }.getOrElse { defaultLabels().also(::save) }
    }

    @Synchronized
    fun save(labels: List<AoiLabel>) {
        file.writeText(JSONArray().apply {
            labels.forEach { label ->
                put(JSONObject().apply {
                    put("id", label.id)
                    put("name", label.name)
                    put("colorArgb", label.colorArgb.toLong())
                    put("archived", label.archived)
                })
            }
        }.toString(2))
    }

    fun nextColor(labels: List<AoiLabel>): Int = DEFAULT_COLORS[labels.size % DEFAULT_COLORS.size]

    private fun defaultLabels() = mutableListOf(
        AoiLabel(BodyTarget.HEAD.name, "Head", DEFAULT_COLORS[0]),
        AoiLabel(BodyTarget.CHEST.name, "Chest", DEFAULT_COLORS[1]),
        AoiLabel(BodyTarget.ABDOMEN.name, "Abdomen", DEFAULT_COLORS[2]),
        AoiLabel("ARM", "Arm", DEFAULT_COLORS[3]),
        AoiLabel("HAND", "Hand", DEFAULT_COLORS[4]),
        AoiLabel(BodyTarget.LEG.name, "Leg", DEFAULT_COLORS[5]),
        AoiLabel(BodyTarget.FOOT.name, "Foot", DEFAULT_COLORS[6]),
        AoiLabel("OTHER", "Other", DEFAULT_COLORS[7]),
    )

    companion object {
        val DEFAULT_COLORS = intArrayOf(
            0xFF22C55E.toInt(), 0xFF38BDF8.toInt(), 0xFFF97316.toInt(), 0xFFA855F7.toInt(),
            0xFFF43F5E.toInt(), 0xFFEAB308.toInt(), 0xFF14B8A6.toInt(), 0xFF6366F1.toInt(),
        )
    }
}

class ExperimentStore(private val context: Context) {
    private val root = File(context.filesDir, "experiments")
    private val legacyRoot = File(root, "current")
    private val legacyManifest = File(legacyRoot, "manifest.json")
    private val versionsDirectory = File(root, "versions")
    private val videoDirectory = File(root, "videos")

    init {
        versionsDirectory.mkdirs()
        videoDirectory.mkdirs()
    }

    @Synchronized
    fun list(includeArchived: Boolean = false): List<Experiment> {
        migrateLegacyIfNeeded()
        val decoded = versionsDirectory.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { decode(JSONObject(file.readText())) }.getOrNull() }
        if (decoded.size == 1) assignLegacySessions(decoded.single())
        return decoded
            .filter { includeArchived || !it.archived }
            .sortedByDescending { it.version }
    }

    @Synchronized
    fun load(id: String? = null): Experiment {
        migrateLegacyIfNeeded()
        val manifest = id?.let(::manifestFor)
        val selected = when {
            manifest?.exists() == true -> manifest
            else -> versionsDirectory.listFiles { file -> file.extension == "json" }
                .orEmpty()
                .maxByOrNull { file -> runCatching { JSONObject(file.readText()).optInt("version", 0) }.getOrDefault(0) }
        } ?: return createNew()
        return runCatching {
            val experiment = decode(JSONObject(selected.readText()))
            hydrateLabels(experiment)
            var changed = false
            experiment.videos.filter { it.sourceFrameCount <= 0L && File(it.localPath).exists() }.forEach { video ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(video.localPath)
                    video.sourceFrameCount = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
                        ?.toLongOrNull() ?: 0L
                    changed = changed || video.sourceFrameCount > 0L
                } finally {
                    retriever.release()
                }
            }
            if (changed) save(experiment)
            experiment
        }.getOrElse { Experiment() }
    }

    @Synchronized
    fun save(experiment: Experiment) {
        versionsDirectory.mkdirs()
        experiment.updatedAtMs = System.currentTimeMillis()
        manifestFor(experiment.id).writeText(encode(experiment).toString(2))
    }

    @Synchronized
    fun createNew(name: String = "New experiment version"): Experiment {
        val nextVersion = (list(includeArchived = true).maxOfOrNull { it.version } ?: 0) + 1
        return Experiment(name = name, version = nextVersion).also(::save)
    }

    @Synchronized
    fun duplicate(source: Experiment): Experiment {
        val now = System.currentTimeMillis()
        val copy = decode(encode(source)).copy(
            id = UUID.randomUUID().toString(),
            name = "${source.name} (copy)",
            status = Experiment.STATUS_DRAFT,
            version = (list(includeArchived = true).maxOfOrNull { it.version } ?: 0) + 1,
            createdAtMs = now,
            updatedAtMs = now,
            archived = false,
        )
        save(copy)
        return copy
    }

    @Synchronized
    fun delete(experiment: Experiment): Boolean {
        if (hasRuns(experiment.id)) return false
        return manifestFor(experiment.id).delete()
    }

    fun archive(experiment: Experiment) {
        experiment.archived = true
        save(experiment)
    }

    fun hasRuns(versionId: String): Boolean = sessionSummaries(versionId).isNotEmpty()

    fun runCount(versionId: String): Int = sessionSummaries(versionId).size

    fun sessionSummaries(versionId: String): List<JSONObject> = File(context.filesDir, "sessions")
        .listFiles { file -> file.isDirectory }
        .orEmpty()
        .mapNotNull { directory ->
            val summary = File(directory, "session_summary.json")
            if (!summary.exists()) null else runCatching { JSONObject(summary.readText()) }.getOrNull()
        }
        .filter { summary -> summary.optString("experimentId") == versionId }
        .sortedByDescending { it.optLong("completedAtMs", it.optLong("startedAtMs", 0L)) }

    fun sessionDirectory(sessionId: String) = File(context.filesDir, "sessions/$sessionId")

    fun snapshot(experiment: Experiment): JSONObject = encode(experiment)

    fun fromSnapshot(snapshot: JSONObject): Experiment = decode(snapshot).also(::hydrateLabels)

    private fun manifestFor(id: String) = File(versionsDirectory, "$id.json")

    private fun hydrateLabels(experiment: Experiment) {
        val library = LabelLibraryStore(context).load()
        val referenced = buildSet {
            experiment.videos.forEach { video ->
                if (video.labelId.isNotBlank()) add(video.labelId)
                video.additionalAois.mapTo(this) { it.labelId }
            }
        }
        referenced.filter { id -> experiment.labels.none { it.id == id } }.forEachIndexed { index, id ->
            val reusable = library.firstOrNull { it.id == id }
            experiment.labels += reusable?.copy() ?: AoiLabel(
                id = id,
                name = id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() },
                colorArgb = LabelLibraryStore.DEFAULT_COLORS[index % LabelLibraryStore.DEFAULT_COLORS.size],
            )
        }
    }

    private fun migrateLegacyIfNeeded() {
        if (versionsDirectory.listFiles { file -> file.extension == "json" }.orEmpty().isNotEmpty()) return
        if (!legacyManifest.exists()) return
        runCatching {
            val legacy = decode(JSONObject(legacyManifest.readText()))
            val migrated = legacy.copy(
                id = UUID.randomUUID().toString(),
                version = legacy.version.coerceAtLeast(1),
                createdAtMs = legacyManifest.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis(),
            )
            save(migrated)
        }
    }

    private fun assignLegacySessions(experiment: Experiment) {
        File(context.filesDir, "sessions").listFiles { file -> file.isDirectory }.orEmpty().forEach { directory ->
            val summaryFile = File(directory, "session_summary.json")
            if (!summaryFile.exists()) return@forEach
            runCatching {
                val summary = JSONObject(summaryFile.readText())
                var changed = false
                if (summary.optString("experimentId").isBlank()) {
                    summary.put("experimentId", experiment.id)
                    changed = true
                }
                if (summary.optString("experimentId") == experiment.id && !summary.has("experimentSnapshot")) {
                    summary.put("experimentSnapshot", encode(experiment))
                    changed = true
                }
                if (changed) {
                    summaryFile.writeText(summary.toString(2))
                }
            }
        }
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
            sourceFrameCount = 0L,
            target = target,
            labelId = "",
        )
    }

    fun deleteVideo(video: ExperimentVideo) {
        val references = list(includeArchived = true)
            .sumOf { experiment -> experiment.videos.count { it.localPath == video.localPath } }
        val referencedElsewhere = references > 1
        if (!referencedElsewhere) File(video.localPath).delete()
    }

    private fun encode(experiment: Experiment): JSONObject = JSONObject().apply {
        put("schemaVersion", 3)
        put("id", experiment.id)
        put("name", experiment.name)
        put("status", experiment.status)
        put("version", experiment.version)
        put("createdAtMs", experiment.createdAtMs)
        put("updatedAtMs", experiment.updatedAtMs)
        put("archived", experiment.archived)
        put("labels", JSONArray().apply {
            experiment.labels.forEach { label ->
                put(JSONObject().apply {
                    put("id", label.id)
                    put("name", label.name)
                    put("colorArgb", label.colorArgb.toLong())
                    put("archived", label.archived)
                })
            }
        })
        put("videos", JSONArray().apply {
            experiment.videos.forEach { video ->
                put(JSONObject().apply {
                    put("id", video.id)
                    put("displayName", video.displayName)
                    put("localPath", video.localPath)
                    put("durationMs", video.durationMs)
                    put("width", video.width)
                    put("height", video.height)
                    put("sourceFrameCount", video.sourceFrameCount)
                    put("target", video.target.name)
                    put("labelId", video.labelId)
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
                    put("additionalAois", JSONArray().apply {
                        video.additionalAois.forEach { track ->
                            put(JSONObject().apply {
                                put("id", track.id)
                                put("target", track.target.name)
                                put("labelId", track.labelId)
                                put("keyframes", encodeKeyframes(track.keyframes))
                            })
                        }
                    })
                })
            }
        })
    }

    private fun decode(json: JSONObject): Experiment = Experiment(
        id = json.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        name = json.optString("name", "Five-region video experiment"),
        status = json.optString("status", Experiment.STATUS_DRAFT),
        version = json.optInt("version", 1).coerceAtLeast(1),
        createdAtMs = json.optLong("createdAtMs", System.currentTimeMillis()),
        updatedAtMs = json.optLong("updatedAtMs", System.currentTimeMillis()),
        archived = json.optBoolean("archived", false),
        labels = mutableListOf<AoiLabel>().apply {
            val labelArray = json.optJSONArray("labels") ?: JSONArray()
            for (index in 0 until labelArray.length()) {
                val item = labelArray.getJSONObject(index)
                add(AoiLabel(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    colorArgb = item.optLong("colorArgb", LabelLibraryStore.DEFAULT_COLORS[index % LabelLibraryStore.DEFAULT_COLORS.size].toLong()).toInt(),
                    archived = item.optBoolean("archived", false),
                ))
            }
        },
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
                    sourceFrameCount = item.optLong("sourceFrameCount", 0L),
                    target = runCatching { BodyTarget.valueOf(item.getString("target")) }
                        .getOrDefault(BodyTarget.HEAD),
                    labelId = item.optString("labelId").takeIf { it.isNotBlank() }
                        ?: runCatching { BodyTarget.valueOf(item.getString("target")).name }.getOrDefault(BodyTarget.HEAD.name),
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
                    additionalAois = mutableListOf<AoiTrack>().apply {
                        val tracks = item.optJSONArray("additionalAois") ?: JSONArray()
                        for (trackIndex in 0 until tracks.length()) {
                            val track = tracks.getJSONObject(trackIndex)
                            add(AoiTrack(
                                id = track.optString("id", UUID.randomUUID().toString()),
                                target = runCatching { BodyTarget.valueOf(track.getString("target")) }
                                    .getOrDefault(BodyTarget.HEAD),
                                labelId = track.optString("labelId").takeIf { it.isNotBlank() }
                                    ?: runCatching { BodyTarget.valueOf(track.getString("target")).name }.getOrDefault(BodyTarget.HEAD.name),
                                keyframes = decodeKeyframes(track.optJSONArray("keyframes") ?: JSONArray()),
                            ))
                        }
                    },
                ))
            }
        },
    )

    private fun encodeKeyframes(keyframes: List<RoiKeyframe>) = JSONArray().apply {
        keyframes.sortedBy { it.timestampMs }.forEach { keyframe ->
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
    }

    private fun decodeKeyframes(frames: JSONArray) = mutableListOf<RoiKeyframe>().apply {
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
    }
}
