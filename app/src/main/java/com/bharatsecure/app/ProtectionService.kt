package com.bharatsecure.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bharatsecure.app.scam.ScamLevel
import com.bharatsecure.app.scam.ScamType
import com.bharatsecure.app.scam.ScamVerdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vosk.Model
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Protection Mode: records the microphone (call on speaker) to a .wav file and
 * transcribes it live with an offline Vosk model. Stops when the call ends or
 * when the user taps Stop, then saves the transcript next to the audio.
 */
class ProtectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null
    @Volatile private var stopRequested = false
    private var lastNotifyMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        if (recordJob?.isActive == true) return START_NOT_STICKY

        val source = intent?.getStringExtra(EXTRA_SOURCE)
            ?.let { runCatching { CallSource.valueOf(it) }.getOrNull() } ?: CallSource.MANUAL
        val caller = intent?.getStringExtra(EXTRA_CALLER)

        ProtectionState.update {
            ProtectionUiState(
                running = true, source = source, caller = caller,
                startedAtMs = System.currentTimeMillis(), status = "Starting…",
            )
        }
        try {
            ServiceCompat.startForeground(
                this, Notifier.ID_PROTECTION,
                Notifier.protectionNotification(this, ProtectionState.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (e: Exception) {
            // Android refuses a microphone service started from the background.
            Log.e(TAG, "startForeground failed", e)
            ProtectionState.update { it.copy(running = false, status = "Could not start: ${e.message}") }
            stopSelf()
            return START_NOT_STICKY
        }

        instance = this
        stopRequested = false
        recordJob = scope.launch { record(source, caller) }
        return START_NOT_STICKY
    }

    fun requestStop() {
        stopRequested = true
        if (recordJob?.isActive != true) finish()
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun record(source: CallSource, caller: String?) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "${stamp}_${source.name.lowercase()}"
        val dir = Recordings.dir(this)
        val wavFile = File(dir, "$base.wav")
        val txtFile = File(dir, "$base.txt")

        CallAudioAccessibilityService.setBadgeVisible(true)
        var sourceIndex = 0
        var audio: AudioRecord = openRecorder(SOURCES[sourceIndex].second) ?: run {
            setStatus("Microphone unavailable")
            finish()
            return
        }

        // Online (Groq Whisper, English) when chosen and a key is saved;
        // otherwise the offline Vosk models.
        val wantsOnline = SpeechModels.engine(this) == SpeechEngine.ONLINE
        val apiKey = SpeechModels.apiKey(this)
        val cloud = if (wantsOnline && apiKey.isNotBlank()) CloudTranscriber(apiKey, SAMPLE_RATE) else null
        val transcriber = if (cloud == null) loadTranscriber() else null
        val engineLabel = cloud?.label ?: transcriber?.label
        val lines = mutableListOf<String>()

        // Laptop RAG server (rag/server.py), when an address is saved. Each new
        // transcript line is sent to it; its verdict drives the banner and score,
        // and a rise in its level raises an alert.
        val ragUrl = RagSettings.url(this)
        var ragSent = 0
        var ragRank = 0 // 0 SAFE, 1 WARNING, 2 SCAM: only alert when it goes up
        var lastRag: RagUpdate? = null
        val rag = if (ragUrl.isBlank()) null else RagLink(
            ragUrl,
            onStatus = { status -> ProtectionState.update { it.copy(ragStatus = status) } },
            onUpdate = { u ->
                lastRag = u
                ProtectionState.update { it.copy(rag = u, scamScore = u.score) }
                val rank = when (u.level) { "SCAM" -> 2; "WARNING" -> 1; else -> 0 }
                if (rank > ragRank) {
                    ragRank = rank
                    val type = ScamType.entries.firstOrNull { it.id == u.type } ?: ScamType.OTHER
                    val level = if (rank == 2) ScamLevel.SURE else ScamLevel.WARNING
                    val verdict = ScamVerdict(level, type, u.score)
                    ProtectionState.update { it.copy(verdict = verdict) }
                    Notifier.showScamAlert(this, verdict,
                        reason = listOfNotNull(u.reason, u.advice).joinToString(" ").ifBlank { null })
                }
            },
        )

        // Sends new transcript lines to the RAG server. The alert level only
        // ever goes up (see onUpdate): a scammer turning polite later doesn't
        // make the call safe.
        fun judge() {
            while (rag != null && ragSent < lines.size) rag.send(lines[ragSent++])
        }
        // Android hands back all-zero audio when it mutes the mic during a call.
        // When that happens we move on to the next audio source and note what each did.
        var heardAnything = false
        var silentChunks = 0
        val sourceLog = mutableListOf<String>()
        try {
            WavWriter(wavFile, SAMPLE_RATE).use { wav ->
                audio.startRecording()
                setStatus(
                    when {
                        cloud != null -> "Recording · online engine, text every ~6 s"
                        transcriber != null && wantsOnline -> "No API key saved, using offline (${transcriber.label})"
                        transcriber != null -> "Recording and transcribing (${transcriber.label})"
                        else -> "Recording (no speech model, transcript off)"
                    }
                )
                val buf = ShortArray(CHUNK)
                val boosted = ShortArray(CHUNK)
                // Call audio heard through the speaker arrives ~10x quieter than
                // normal speech (RMS ~40-130 vs ~600-1300), too faint for either
                // speech engine. An automatic gain brings speech up to normal
                // level; it adapts to the loudness of recent speech only, so
                // pauses don't make it jump. The .wav keeps the original audio.
                var speechRms = TARGET_RMS
                while (scope.isActive && !stopRequested) {
                    val n = audio.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    wav.write(buf, n)
                    val raw = rawRms(buf, n)
                    val isSpeech = raw > NOISE_FLOOR
                    if (isSpeech) speechRms = 0.9 * speechRms + 0.1 * raw
                    val gain = (TARGET_RMS / speechRms).coerceIn(1.0, MAX_GAIN)
                    for (i in 0 until n) {
                        boosted[i] = (buf[i] * gain).roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    val level = (raw * gain / 6000.0).coerceIn(0.0, 1.0).toFloat()
                    if (raw > 0.0) {
                        if (silentChunks >= SILENT_SWITCH_CHUNKS || !heardAnything) {
                            sourceLog += "${SOURCES[sourceIndex].first}=audio"
                        }
                        heardAnything = true
                        silentChunks = 0
                    } else if (++silentChunks == SILENT_SWITCH_CHUNKS && sourceIndex < SOURCES.lastIndex) {
                        sourceLog += "${SOURCES[sourceIndex].first}=silent"
                        Log.i(TAG, "${SOURCES[sourceIndex].first} silent, switching")
                        runCatching { audio.stop() }
                        audio.release()
                        var next: AudioRecord? = null
                        while (next == null && sourceIndex < SOURCES.lastIndex) {
                            sourceIndex++
                            next = openRecorder(SOURCES[sourceIndex].second)
                            if (next == null) sourceLog += "${SOURCES[sourceIndex].first}=unavailable"
                        }
                        audio = next ?: openRecorder(SOURCES[0].second) ?: break
                        audio.startRecording()
                        silentChunks = 0
                        setStatus("Mic silent, trying ${SOURCES[sourceIndex].first}")
                    }

                    if (cloud != null) {
                        cloud.accept(boosted, n, isSpeech)
                        var got = false
                        while (true) {
                            lines += cloud.poll() ?: break
                            got = true
                        }
                        if (got) {
                            ProtectionState.update { it.copy(transcript = lines.toList(), partial = "", level = level) }
                            judge()
                            refreshNotification(force = true)
                        } else {
                            val error = cloud.lastError?.let { "Online engine: $it" }.orEmpty()
                            ProtectionState.update { it.copy(partial = error, level = level) }
                        }
                        continue
                    }
                    if (transcriber == null) {
                        ProtectionState.update { it.copy(level = level) }
                        continue
                    }
                    val sentence = transcriber.accept(boosted, n)
                    if (sentence != null) {
                        lines += sentence
                        ProtectionState.update { it.copy(transcript = lines.toList(), partial = "", level = level) }
                        judge()
                        refreshNotification(force = true)
                    } else {
                        val partial = transcriber.partial()
                        ProtectionState.update { it.copy(partial = partial, level = level) }
                        refreshNotification(force = false)
                    }
                }
                transcriber?.finish()?.let {
                    lines += it
                    judge()
                }
                cloud?.let {
                    setStatus("Finishing online transcript…")
                    val rest = it.finish()
                    if (rest.isNotEmpty()) {
                        lines += rest
                        judge()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed", e)
            setStatus("Recording error: ${e.message}")
        } finally {
            runCatching { audio.stop() }
            audio.release()
            transcriber?.close()
            cloud?.close()
            rag?.close()
        }

        txtFile.writeText(buildString {
            appendLine("Source: ${source.label}")
            appendLine("Caller: ${caller ?: "unknown"}")
            appendLine("Started: $stamp")
            appendLine("Speech engine: ${engineLabel ?: "none"}")
            if (rag != null) {
                appendLine("Laptop RAG ($ragUrl): " + (lastRag?.let {
                    "${it.level} ${it.typeName.orEmpty()} (score ${"%.2f".format(it.score)})".trim()
                } ?: "no reply"))
            }
            appendLine("Mic sources: ${sourceLog.joinToString(", ").ifEmpty { SOURCES[sourceIndex].first }}")
            appendLine()
            if (!heardAnything) appendLine("(Silent: Android muted the microphone. Turn on Accessibility in Setup.)")
            if (engineLabel == null) appendLine("(No speech model was installed, so there is no transcript.)")
            cloud?.lastError?.let { appendLine("(Online engine error: $it)") }
            lines.forEach { appendLine(it) }
        })
        ProtectionState.update { it.copy(transcript = lines.toList(), partial = "") }
        finish(if (heardAnything) "Saved" else "Saved, but the audio was silent (mic muted by Android)")
    }

    @SuppressLint("MissingPermission") // checked in start()
    private fun openRecorder(audioSource: Int): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val audio = try {
            AudioRecord(
                audioSource, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, CHUNK * 2 * 4),
            )
        } catch (e: Exception) {
            return null
        }
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release()
            return null
        }
        return audio
    }

    /** Loads the selected language's models; for Hinglish, uses whichever of the two are installed. */
    private fun loadTranscriber(): Transcriber? {
        val language = SpeechModels.selectedLanguage(this)
        val installed = SpeechModels.installedModels(this, language)
        if (installed.isEmpty()) return null
        setStatus("Loading ${language.label} speech model…")
        return try {
            Transcriber(
                installed.map { it to Model(SpeechModels.modelDir(this, it).absolutePath) },
                SAMPLE_RATE.toFloat(),
            )
        } catch (e: Exception) {
            Log.e(TAG, "model load failed", e)
            null
        }
    }

    private fun finish(status: String = "Saved") {
        CallAudioAccessibilityService.setBadgeVisible(false)
        ProtectionState.update { it.copy(running = false, level = 0f, status = status) }
        instance = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun setStatus(status: String) {
        ProtectionState.update { it.copy(status = status) }
        refreshNotification(force = true)
    }

    private fun refreshNotification(force: Boolean) {
        if (!ProtectionState.state.value.running) return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotifyMs < 1_000) return
        lastNotifyMs = now
        getSystemService(NotificationManager::class.java)
            .notify(Notifier.ID_PROTECTION, Notifier.protectionNotification(this, ProtectionState.state.value))
    }

    private fun rawRms(buf: ShortArray, n: Int): Double {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
        return sqrt(sum / n)
    }

    override fun onDestroy() {
        stopRequested = true
        scope.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ProtectionService"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = 1_600 // 100 ms
        private const val SILENT_SWITCH_CHUNKS = 20 // 2 s of pure silence

        // Automatic gain (see record()): speech is brought up to about
        // TARGET_RMS, by at most MAX_GAIN. Below NOISE_FLOOR is a pause.
        private const val TARGET_RMS = 2000.0
        private const val MAX_GAIN = 20.0
        private const val NOISE_FLOOR = 30.0

        // VOICE_RECOGNITION first: on a Galaxy S23 (Android 14) with the accessibility
        // badge showing, it hears calls while MIC gets silence.
        private val SOURCES = listOf(
            "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "MIC" to MediaRecorder.AudioSource.MIC,
            "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "CAMCORDER" to MediaRecorder.AudioSource.CAMCORDER,
            "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
        )

        const val ACTION_STOP = "com.bharatsecure.app.STOP"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_CALLER = "caller"

        @Volatile private var instance: ProtectionService? = null

        /** Must be called while the app is visible. Returns false if mic permission is missing. */
        fun start(context: Context, source: CallSource, caller: String?): Boolean {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) return false
            val intent = Intent(context, ProtectionService::class.java)
                .putExtra(EXTRA_SOURCE, source.name)
                .putExtra(EXTRA_CALLER, caller)
            ContextCompat.startForegroundService(context, intent)
            return true
        }

        fun stop() {
            instance?.requestStop()
        }

        /** Called by the call detectors when a call ends. */
        fun stopIfSource(context: Context, source: CallSource) {
            val state = ProtectionState.state.value
            if (state.running && state.source == source) stop()
        }
    }
}
