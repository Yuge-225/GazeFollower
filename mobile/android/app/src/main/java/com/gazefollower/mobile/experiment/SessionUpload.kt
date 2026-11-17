package com.gazefollower.mobile.experiment

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import com.gazefollower.mobile.PARTICIPANT_CODE
import com.gazefollower.mobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.Executors

object SessionUpload {
    private val executor = Executors.newSingleThreadExecutor()
    private val uploading = mutableSetOf<String>()

    fun button(context: Context, sessionId: String) = Button(context).apply {
        text = "Upload to Website"
        isAllCaps = false
        setTextColor(context.getColor(R.color.button_text))
        backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_background))
        setOnClickListener { configure(this, sessionId) }
    }

    private fun configure(button: Button, sessionId: String) {
        val context = button.context
        if (sessionId in uploading) return
        val directory = ExperimentStore(context).sessionDirectory(sessionId)
        val summary = runCatching {
            JSONObject(directory.resolve("session_summary.json").readText())
        }.getOrElse {
            Toast.makeText(context, "Saved test data is unavailable.", Toast.LENGTH_LONG).show()
            return
        }
        val settings = context.getSharedPreferences("research_website", Context.MODE_PRIVATE)
        val address = EditText(context).apply {
            hint = "Website URL"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setText(settings.getString("address", ""))
        }
        val password = EditText(context).apply {
            hint = "Website password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        val isLegacy = summary.optString("participantCode").isBlank()
        val participant = EditText(context).apply { hint = "Participant code"; setSingleLine() }
        val fields = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, 0, padding, 0)
            addView(address)
            addView(password)
            if (isLegacy) addView(participant)
        }
        val dialog = AlertDialog.Builder(context).setTitle("Upload to Website")
            .setView(fields).setNegativeButton("Cancel", null)
            .setPositiveButton("Upload", null).show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val uri = runCatching { URI(address.text.toString().trim().trimEnd('/')) }.getOrNull()
            if (uri?.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null || !uri.path.isNullOrEmpty() ||
                (uri.scheme != "https" && !(uri.scheme == "http" && uri.host == "127.0.0.1"))) {
                address.error = "Use an HTTPS website URL or the USB test address."
                return@setOnClickListener
            }
            val secret = password.text.toString()
            if (secret.isBlank()) {
                password.error = "Enter the website password."
                return@setOnClickListener
            }
            val code = if (isLegacy) participant.text.toString().trim() else summary.getString("participantCode")
            if (!PARTICIPANT_CODE.matches(code)) {
                participant.error = "Enter a participant code."
                return@setOnClickListener
            }
            val connection = context.getSystemService(ConnectivityManager::class.java)
            if (uri.host != "127.0.0.1" && connection.activeNetwork == null) {
                Toast.makeText(context, "Connect to the internet, then tap Upload again.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            settings.edit().putString("address", uri.toString()).apply()
            summary.put("participantCode", code)
            dialog.dismiss()
            uploading.add(sessionId)
            button.isEnabled = false
            button.text = "Checking website…"
            executor.execute {
                val result = runCatching {
                    upload(uri, secret, directory, summary)
                }
                Handler(Looper.getMainLooper()).post {
                    uploading.remove(sessionId)
                    button.isEnabled = true
                    button.text = result.getOrElse { "Retry Upload" }
                    result.exceptionOrNull()?.let { error ->
                        Toast.makeText(context, error.message ?: "Upload failed. Tap Retry Upload.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    internal fun upload(uri: URI, password: String, directory: File, summary: JSONObject): String {
        val id = summary.getString("sessionId")
        val credential = Base64.encodeToString("researcher:$password".toByteArray(), Base64.NO_WRAP)
        fun preflight(hashes: Collection<String>) = request(uri, "/api/check", credential, JSONObject().apply {
            put("sessionId", id)
            put("assetHashes", JSONArray(hashes))
        })
        if (preflight(emptyList()).getBoolean("sessionExists")) return "Already Uploaded"

        val snapshot = summary.optJSONObject("experimentSnapshot")
            ?: throw IOException("This test has no saved video annotations.")
        val trials = summary.getJSONArray("trials")
        val played = (0 until trials.length()).map { trials.getJSONObject(it).getString("videoId") }.toSet()
        val videos = snapshot.getJSONArray("videos")
        val trialVideos = JSONArray()
        val files = linkedMapOf<String, File>()
        for (index in 0 until videos.length()) {
            val video = videos.getJSONObject(index)
            if (video.getString("id") !in played) continue
            val file = File(video.getString("localPath"))
            if (!file.isFile) throw IOException("The saved stimulus ${video.getString("displayName")} is unavailable.")
            val hash = sha256(file)
            files[hash] = file
            video.remove("localPath")
            video.put("assetHash", hash)
            trialVideos.put(video)
        }
        snapshot.put("videos", trialVideos)
        val check = preflight(files.keys)
        if (check.getBoolean("sessionExists")) return "Already Uploaded"
        val missing = check.getJSONArray("missingAssets")
        for (index in 0 until missing.length()) {
            val hash = missing.getString(index)
            val file = files.getValue(hash)
            exchange(uri, "/api/assets/$hash", credential, "PUT", "application/octet-stream", file.length()) { output ->
                file.inputStream().use { it.copyTo(output) }
            }
        }
        val captured = directory.resolve("captured_samples.json")
        val answer = request(uri, "/api/sessions", credential, JSONObject().apply {
            put("summary", summary)
            if (captured.exists()) put("samples", JSONArray(captured.readText()))
            else put("legacyGazeCsv", directory.resolve("gaze_samples.csv").readText())
        })
        return if (answer.getBoolean("alreadyUploaded")) "Already Uploaded" else "Uploaded"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun request(uri: URI, path: String, credential: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray()
        return exchange(uri, path, credential, "POST", "application/json", bytes.size.toLong()) { it.write(bytes) }
    }

    private fun exchange(
        uri: URI, path: String, credential: String, method: String, contentType: String,
        length: Long, write: (OutputStream) -> Unit,
    ): JSONObject {
        val connection = URI(uri.toString() + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 45_000
            connection.doOutput = true
            connection.setRequestProperty("Authorization", "Basic $credential")
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use(write)
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 401) throw IOException("Website password was rejected.")
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val detail = message?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                throw IOException(detail?.takeIf { it.isNotBlank() } ?: "Website returned HTTP ${connection.responseCode}.")
            }
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (error: java.net.ConnectException) {
            throw IOException("Cannot reach the website. Check the connection and website URL.", error)
        } catch (error: java.net.SocketTimeoutException) {
            throw IOException("The website did not respond in time. Tap Retry Upload.", error)
        } finally {
            connection.disconnect()
        }
    }
}
