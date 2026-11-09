package com.bharatsecure.app

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** A score update from the laptop RAG server (rag/server.py). */
data class RagUpdate(
    val score: Double,
    val level: String,        // SAFE / WARNING / SCAM
    val type: String?,        // phishing_otp / digital_arrest / banking / other_scam
    val typeName: String?,
    val reason: String?,
    val advice: String?,
)

/** Where the laptop server is. Saved on the phone; empty = not used. */
object RagSettings {
    private const val PREFS = "rag"
    private const val KEY_URL = "server_url"

    fun url(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_URL, null).orEmpty()

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_URL, normalize(url)).apply()
    }

    /** "10.30.137.250:8000" -> "http://10.30.137.250:8000" */
    fun normalize(url: String): String {
        val u = url.trim().trimEnd('/')
        return if (u.isEmpty() || u.startsWith("http://") || u.startsWith("https://")) u else "http://$u"
    }
}

/** Plain HTTP calls to rag/server.py. Blocking: call from a background thread. */
object RagClient {
    fun health(base: String): JSONObject = JSONObject(request("GET", "$base/health", null))

    fun startSession(base: String): String = JSONObject(request("POST", "$base/sessions", null)).getString("session_id")

    fun sendText(base: String, sessionId: String, text: String): RagUpdate {
        val body = JSONObject().put("text", text).toString()
        val r = JSONObject(request("POST", "$base/sessions/$sessionId/text", body))
        val llm = r.optJSONObject("details")?.optJSONObject("llm")
        return RagUpdate(
            score = r.optDouble("score", 0.0),
            level = r.optString("level", "SAFE"),
            type = r.optString("type").takeIf { it.isNotBlank() && it != "null" },
            typeName = r.optString("type_name").takeIf { it.isNotBlank() && it != "null" },
            reason = llm?.optString("reason")?.takeIf { it.isNotBlank() },
            advice = llm?.optString("advice")?.takeIf { it.isNotBlank() },
        )
    }

    fun endSession(base: String, sessionId: String) {
        request("DELETE", "$base/sessions/$sessionId", null)
    }

    private fun request(method: String, url: String, json: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 4_000
            conn.readTimeout = 30_000 // DeepSeek can take a few seconds
            if (json != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(100)}")
            return body
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * One call's connection to the laptop: opens a session, sends each new
 * transcript line in order on a background thread, closes at the end.
 * If the laptop can't be reached it keeps retrying on the next line, and
 * the on-phone model keeps protecting the call meanwhile.
 */
class RagLink(
    private val base: String,
    private val onStatus: (String) -> Unit,
    private val onUpdate: (RagUpdate) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var sessionId: String? = null

    init {
        onStatus("Connecting to $base…")
        worker.execute { ensureSession() }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        worker.execute {
            val id = ensureSession() ?: return@execute
            try {
                onUpdate(RagClient.sendText(base, id, text))
                onStatus("Connected")
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                onStatus("Laptop offline: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    fun close() {
        worker.execute {
            sessionId?.let { id -> runCatching { RagClient.endSession(base, id) } }
            sessionId = null
        }
        worker.shutdown()
    }

    private fun ensureSession(): String? {
        sessionId?.let { return it }
        return try {
            RagClient.startSession(base).also {
                sessionId = it
                onStatus("Connected")
            }
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            onStatus("Laptop offline: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    companion object {
        private const val TAG = "RagLink"
    }
}
