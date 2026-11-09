package com.bharatsecure.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** One downloadable Vosk model. */
enum class SpeechModel(val label: String, val modelName: String, val sizeMb: Int) {
    HINDI("Hindi", "vosk-model-small-hi-0.22", 42),
    ENGLISH_IN("English (India)", "vosk-model-small-en-in-0.4", 36);

    val url: String get() = "https://alphacephei.com/vosk/models/$modelName.zip"
}

/**
 * What the user picks. Hinglish has no model of its own: it runs the Hindi and
 * English models side by side and keeps whichever is surer of each sentence.
 */
enum class SpeechLanguage(val label: String, val models: List<SpeechModel>) {
    HINGLISH("Hinglish (mixed)", listOf(SpeechModel.HINDI, SpeechModel.ENGLISH_IN)),
    HINDI("Hindi", listOf(SpeechModel.HINDI)),
    ENGLISH_IN("English (India)", listOf(SpeechModel.ENGLISH_IN)),
}

enum class SpeechEngine(val label: String) {
    ONLINE("Online · best accuracy"),
    OFFLINE("Offline · private"),
}

/**
 * Speech settings. Offline uses the Vosk models below (audio never leaves the
 * phone); Online uses CloudTranscriber with the user's own Groq API key.
 */
object SpeechModels {
    private const val PREFS = "speech"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_ENGINE = "engine"
    private const val KEY_API = "groq_api_key"
    private const val MARKER = ".installed"

    fun engine(context: Context): SpeechEngine {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENGINE, null)
        return SpeechEngine.entries.firstOrNull { it.name == name } ?: SpeechEngine.OFFLINE
    }

    fun selectEngine(context: Context, engine: SpeechEngine) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ENGINE, engine.name).apply()
    }

    fun apiKey(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_API, null).orEmpty()

    fun saveApiKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_API, key.trim()).apply()
    }

    fun selectedLanguage(context: Context): SpeechLanguage {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
        return SpeechLanguage.entries.firstOrNull { it.name == name } ?: SpeechLanguage.HINGLISH
    }

    fun selectLanguage(context: Context, language: SpeechLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_LANGUAGE, language.name).apply()
    }

    private fun modelsRoot(context: Context) = File(context.filesDir, "models")

    fun modelDir(context: Context, model: SpeechModel) = File(modelsRoot(context), model.modelName)

    fun isInstalled(context: Context, model: SpeechModel) =
        File(modelDir(context, model), MARKER).exists()

    /** The models [language] needs that are already on the phone. */
    fun installedModels(context: Context, language: SpeechLanguage) =
        language.models.filter { isInstalled(context, it) }

    /** Downloads and unzips [model]. [onProgress] gets 0..1. */
    suspend fun download(context: Context, model: SpeechModel, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            val zip = File(context.cacheDir, "${model.modelName}.zip")
            val conn = URL(model.url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    error("Download failed: HTTP ${conn.responseCode}")
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    FileOutputStream(zip).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val root = modelsRoot(context).apply { mkdirs() }
            modelDir(context, model).deleteRecursively()
            unzip(zip, root)
            zip.delete()
            File(modelDir(context, model), MARKER).createNewFile()
        }

    private fun unzip(zip: File, into: File) {
        val rootPath = into.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                val out = File(into, entry.name)
                // Refuse entries that would escape the models folder.
                if (!out.canonicalPath.startsWith(rootPath)) error("Bad zip entry: ${entry.name}")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
            }
        }
    }
}
