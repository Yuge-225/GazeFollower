package com.gazefollower.mobile.experiment

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private val mainThread = Handler(Looper.getMainLooper())
    private val PAIRING_CODE = Regex("[A-Fa-f0-9]{16}")
    private class AuthorizationRejected : IOException()
    private val uploading = mutableSetOf<String>()

    fun button(context: Context, sessionId: String) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val indicator = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(Button(context).apply {
            text = "Upload to Website"
            isAllCaps = false
            setTextColor(context.getColor(R.color.button_text))
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.button_background))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setOnClickListener { configure(this, indicator, sessionId) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(indicator, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun settings(context: Context) {
        val store = UploadAuthorizationStore(context)
        val authorization = store.load()
        val dialog = AlertDialog.Builder(context).setTitle("Upload Settings")
            .setNegativeButton("Close", null)
        if (authorization == null) {
            dialog.setMessage("Pair this phone to upload test results.")
                .setPositiveButton("Pair phone") { _, _ -> pair(context) }
        } else {
            dialog.setMessage("Paired with ${authorization.address}")
                .setPositiveButton("Pair again") { _, _ -> pair(context) }
                .setNeutralButton("Forget pairing") { _, _ -> store.forget() }
        }
        dialog.show()
    }

    private fun configure(button: Button, indicator: ProgressBar, sessionId: String) {
        if (sessionId in uploading) return
        val context = button.context
        val directory = ExperimentStore(context).sessionDirectory(sessionId)
        val summary = runCatching {
            JSONObject(directory.resolve("session_summary.json").readText())
        }.getOrElse {
            toast(context, "Saved test data is unavailable.")
            return
        }

        fun uploadTest(authorization: UploadAuthorization) {
            val existingCode = summary.optString("participantCode")
            if (PARTICIPANT_CODE.matches(existingCode)) {
                startUpload(button, indicator, sessionId, authorization, directory, summary)
                return
            }
            val participant = input(context, "Participant code")
            val dialog = AlertDialog.Builder(context).setTitle("Participant code")
                .setMessage("Add a participant code for this older test.")
                .setView(fields(context, participant)).setNegativeButton("Cancel", null)
                .setPositiveButton("Upload", null).show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = participant.text.toString().trim()
                if (!PARTICIPANT_CODE.matches(code)) {
                    participant.error = "Enter a participant code."
                    return@setOnClickListener
                }
                dialog.dismiss()
                summary.put("participantCode", code)
                startUpload(button, indicator, sessionId, authorization, directory, summary)
            }
        }

        val authorization = UploadAuthorizationStore(context).load()
        if (authorization == null) pair(context, ::uploadTest) else uploadTest(authorization)
    }

    private fun pair(context: Context, onPaired: ((UploadAuthorization) -> Unit)? = null) {
        val store = UploadAuthorizationStore(context)
        val address = input(context, "Website URL", InputType.TYPE_TEXT_VARIATION_URI).apply {
            setText(store.lastAddress())
        }
        val code = input(context, "Pairing code", InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        val dialog = AlertDialog.Builder(context).setTitle("Pair with Website")
            .setMessage("On the website, sign in and choose Pair a phone → Generate code.")
            .setView(fields(context, address, code)).setNegativeButton("Cancel", null)
            .setPositiveButton(if (onPaired == null) "Pair phone" else "Pair and upload", null).show()
        val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        var dismissed = false
        dialog.setOnDismissListener { dismissed = true }
        confirm.setOnClickListener {
            val uri = parseAddress(address.text.toString())
            if (uri == null) {
                address.error = "Use an HTTPS website URL or the USB test address."
                return@setOnClickListener
            }
            val pairingCode = code.text.toString().trim().replace("-", "")
            if (!PAIRING_CODE.matches(pairingCode)) {
                code.error = "Enter the 16-character pairing code."
                return@setOnClickListener
            }
            if (!hasConnection(context, uri)) return@setOnClickListener
            confirm.isEnabled = false
            dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            executor.execute {
                val result = runCatching { redeem(uri, pairingCode).also(store::save) }
                mainThread.post {
                    if (!dismissed) {
                        confirm.isEnabled = true
                        dialog.setCancelable(true)
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                        result.onSuccess { authorization ->
                            dialog.dismiss()
                            if (onPaired == null) toast(context, "Phone paired.")
                            else onPaired(authorization)
                        }.onFailure { error ->
                            code.error = error.message ?: "Could not pair. Generate a new code and try again."
                        }
                    }
                }
            }
        }
    }

    private fun startUpload(
        button: Button,
        indicator: ProgressBar,
        sessionId: String,
        authorization: UploadAuthorization,
        directory: File,
        summary: JSONObject,
    ) {
        val context = button.context
        if (!hasConnection(context, authorization.address) || !uploading.add(sessionId)) return
        button.isEnabled = false
        button.text = "Waiting to upload…"
        indicator.isIndeterminate = true
        indicator.visibility = View.VISIBLE
        executor.execute {
            val result = runCatching {
                upload(authorization, directory, summary) { stage, percent ->
                    mainThread.post {
                        button.text = if (percent == null) stage else "$stage · $percent%"
                        indicator.isIndeterminate = percent == null
                        if (percent != null) indicator.progress = percent
                    }
                }
            }
            mainThread.post {
                uploading.remove(sessionId)
                indicator.visibility = View.GONE
                button.isEnabled = true
                val error = result.exceptionOrNull()
                if (error is AuthorizationRejected) {
                    val store = UploadAuthorizationStore(context)
                    if (store.load() == authorization) store.forget()
                    button.text = "Pair and upload"
                    toast(context, "This pairing has expired or was reset. Tap to pair again.")
                } else {
                    button.text = result.getOrElse { "Retry Upload" }
                    error?.let { toast(context, it.message ?: "Upload failed. Tap Retry Upload.") }
                }
            }
        }
    }

    private fun parseAddress(value: String): URI? {
        val uri = runCatching { URI(value.trim().trimEnd('/')) }.getOrNull() ?: return null
        if (uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null || !uri.path.isNullOrEmpty()) return null
        return uri.takeIf { it.scheme == "https" || (it.scheme == "http" && it.host == "127.0.0.1") }
    }

    private fun hasConnection(context: Context, address: URI): Boolean {
        if (address.host == "127.0.0.1" || context.getSystemService(ConnectivityManager::class.java).activeNetwork != null) return true
        toast(context, "Connect to the internet, then try again.")
        return false
    }

    private fun input(context: Context, label: String, variation: Int = 0) = EditText(context).apply {
        hint = label
        inputType = InputType.TYPE_CLASS_TEXT or variation
        setSingleLine()
    }

    private fun fields(context: Context, vararg items: EditText) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val padding = (24 * resources.displayMetrics.density).toInt()
        setPadding(padding, 0, padding, 0)
        items.forEach { addView(it) }
    }

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    internal fun redeem(uri: URI, code: String): UploadAuthorization {
        val reply = request(uri, "/api/pairing/redeem", null, JSONObject().put("code", code))
        val token = reply.optString("uploadToken")
        if (token.isBlank()) throw IOException("The website did not return a pairing token.")
        return UploadAuthorization(uri, token)
    }

    internal fun upload(
        authorization: UploadAuthorization,
        directory: File,
        summary: JSONObject,
        progress: (stage: String, percent: Int?) -> Unit = { _, _ -> },
    ): String {
        val id = summary.getString("sessionId")
        val uri = authorization.address
        val credential = "Bearer ${authorization.token}"
        fun preflight(hashes: Collection<String>) = request(uri, "/api/check", credential, JSONObject().apply {
            put("sessionId", id)
            put("assetHashes", JSONArray(hashes))
        })
        progress("Connecting to website…", null)
        if (preflight(emptyList()).getBoolean("sessionExists")) return "Already Uploaded"

        progress("Preparing videos…", null)
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
        progress("Checking for existing videos…", null)
        val check = preflight(files.keys)
        if (check.getBoolean("sessionExists")) return "Already Uploaded"
        val missing = check.getJSONArray("missingAssets")
        val pending = (0 until missing.length()).map { index ->
            val hash = missing.getString(index)
            val file = files.getValue(hash)
            val length = file.length()
            if (length <= 0) throw IOException("A video could not be read. Try uploading again.")
            Triple(hash, file, length)
        }
        val totalBytes = pending.sumOf { it.third }
        var sentBytes = 0L
        val buffer = ByteArray(64 * 1024)
        for ((index, item) in pending.withIndex()) {
            val (hash, file, length) = item
            val stage = "Uploading video ${index + 1} of ${pending.size}"
            var lastPercent = (100 * sentBytes / totalBytes).toInt()
            progress(stage, lastPercent)
            exchange(uri, "/api/assets/$hash", credential, "PUT", "application/octet-stream", length) { output ->
                file.inputStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        sentBytes += count
                        val percent = (100 * sentBytes / totalBytes).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            progress(stage, percent)
                        }
                    }
                }
            }
        }
        progress("Saving test results…", null)
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

    private fun request(uri: URI, path: String, credential: String?, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray()
        return exchange(uri, path, credential, "POST", "application/json", bytes.size.toLong()) { it.write(bytes) }
    }

    private fun exchange(
        uri: URI, path: String, credential: String?, method: String, contentType: String,
        length: Long, write: (OutputStream) -> Unit,
    ): JSONObject {
        val connection = URI(uri.toString() + path).toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 90_000
            connection.doOutput = true
            if (credential != null) connection.setRequestProperty("Authorization", credential)
            connection.setRequestProperty("Content-Type", contentType)
            connection.setFixedLengthStreamingMode(length)
            connection.outputStream.use(write)
            if (connection.responseCode !in 200..299) {
                if (connection.responseCode == 401 && credential != null) throw AuthorizationRejected()
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }
                val detail = message?.let { runCatching { JSONObject(it).optString("error") }.getOrNull() }
                throw IOException(detail?.takeIf { it.isNotBlank() } ?: "Website returned HTTP ${connection.responseCode}.")
            }
            return connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
        } catch (error: java.net.ConnectException) {
            throw IOException("Cannot reach the website. Check the connection and website URL.", error)
        } catch (error: java.net.SocketTimeoutException) {
            throw IOException("The website did not respond in time. Try again.", error)
        } finally {
            connection.disconnect()
        }
    }
}
