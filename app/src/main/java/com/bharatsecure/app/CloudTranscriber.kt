package com.bharatsecure.app

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Online speech engine: Whisper large-v3 hosted by Groq, in "translations"
 * mode, so Hindi, English and Hinglish speech all come back as English text.
 * English is what the scam model was mostly trained on.
 *
 * Audio is collected in ~6 s chunks and uploaded one at a time on a
 * background thread; finished text is picked up with [poll]. Only used while
 * Protection Mode is on, and only when the user chose Online and gave a key.
 */
class CloudTranscriber(private val apiKey: String, private val sampleRate: Int) : AutoCloseable {

    val label = "Online: Whisper large-v3 via Groq (to English)"

    private val chunkSamples = sampleRate * CHUNK_SECONDS
    private var buffer = ShortArray(chunkSamples)
    private var filled = 0
    private var speechSamples = 0
    private val results = ConcurrentLinkedQueue<String>()
    private val uploader = Executors.newSingleThreadExecutor()
    private val context = StringBuilder() // recent text, given to Whisper as a hint

    /** Last upload error (bad key, no internet …), or null when the last upload worked. */
    @Volatile var lastError: String? = null
        private set

    /**
     * Adds (already volume-boosted) audio; a full chunk is queued for upload.
     * [isSpeech] says whether this piece was above the noise floor before
     * boosting. Never blocks on the network.
     */
    fun accept(buf: ShortArray, n: Int, isSpeech: Boolean) {
        var offset = 0
        while (offset < n) {
            val take = minOf(n - offset, chunkSamples - filled)
            System.arraycopy(buf, offset, buffer, filled, take)
            filled += take
            offset += take
            if (isSpeech) speechSamples += take
            if (filled == chunkSamples) flushChunk()
        }
    }

    /** Next finished piece of text, or null. */
    fun poll(): String? = results.poll()

    /** Uploads what's left and waits (up to [timeoutSeconds]) for the queue to finish. */
    fun finish(timeoutSeconds: Long = 20): List<String> {
        if (filled > sampleRate) flushChunk() // ignore a final scrap under 1 s
        uploader.shutdown()
        uploader.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)
        return generateSequence { results.poll() }.toList()
    }

    override fun close() {
        uploader.shutdownNow()
    }

    private fun flushChunk() {
        val chunk = buffer.copyOf(filled)
        val speech = speechSamples
        buffer = ShortArray(chunkSamples)
        filled = 0
        speechSamples = 0
        // Silence makes Whisper invent text ("Thank you."), so chunks with
        // almost no speech are skipped. Judged by how long someone spoke, not
        // average loudness: call audio is quiet even when people talk.
        if (speech < sampleRate * MIN_SPEECH_SECONDS) return
        uploader.execute { upload(chunk) }
    }

    private fun upload(chunk: ShortArray) {
        try {
            val text = request(wav(chunk)).trim()
            lastError = null
            if (text.isNotEmpty() && !isHallucination(text)) {
                results += text
                synchronized(context) {
                    context.append(' ').append(text)
                    if (context.length > 400) context.delete(0, context.length - 400)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed", e)
            lastError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun request(audio: ByteArray): String {
        val boundary = "----bharatsecure${System.nanoTime()}"
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            DataOutputStream(conn.outputStream).use { out ->
                fun field(name: String, value: String) {
                    out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    out.write(value.toByteArray(Charsets.UTF_8))
                    out.writeBytes("\r\n")
                }
                field("model", MODEL)
                field("response_format", "json")
                field("temperature", "0")
                val hint = synchronized(context) { context.toString().trim() }
                if (hint.isNotEmpty()) field("prompt", hint)
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(audio)
                out.writeBytes("\r\n--$boundary--\r\n")
            }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val body = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException(
                    when (code) {
                        401 -> "API key rejected (401)"
                        429 -> "rate limit reached (429), slow down"
                        else -> "HTTP $code ${body.take(120)}"
                    }
                )
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("text")
        } finally {
            conn.disconnect()
        }
    }

    private fun wav(samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        fun le32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun le16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); le32(36 + data); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(data)
        for (s in samples) le16(s.toInt())
        return out.toByteArray()
    }

    private fun isHallucination(text: String): Boolean {
        val t = text.lowercase().trim(' ', '.', '!')
        return t in HALLUCINATIONS
    }

    companion object {
        private const val TAG = "CloudTranscriber"
        private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/translations"
        private const val MODEL = "whisper-large-v3"
        private const val CHUNK_SECONDS = 6
        private const val MIN_SPEECH_SECONDS = 0.6
        // Phrases Whisper is known to produce from near-silence or noise.
        private val HALLUCINATIONS = setOf(
            "thank you", "thanks for watching", "thank you for watching", "you", "bye", "okay",
            "please subscribe", "subtitles by the amara.org community",
        )
    }
}
