package com.bharatsecure.app

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Live speech-to-text over one or more Vosk models.
 *
 * With one model it is a plain recognizer. With several (Hinglish: Hindi +
 * English) every model hears the same audio; the first model decides where a
 * sentence ends, and the sentence is taken from whichever model was most
 * confident about it. The choice is per sentence, not per word.
 */
class Transcriber(models: List<Pair<SpeechModel, Model>>, sampleRate: Float) : AutoCloseable {

    private class Engine(val model: SpeechModel, val vosk: Model, val recognizer: Recognizer) {
        /** Sentences this engine finished early, held until the primary finishes its own. */
        val pending = mutableListOf<Heard>()
    }

    private data class Heard(val text: String, val confidence: Double, val words: Int)

    private val engines = models.map { (spec, model) ->
        Engine(spec, model, Recognizer(model, sampleRate).apply { setWords(true) })
    }
    private val primary = engines.first()

    val label: String = engines.joinToString(" + ") { it.model.label }

    /** Feeds audio. Returns a finished sentence, or null while one is still being spoken. */
    fun accept(buf: ShortArray, n: Int): String? {
        var sentenceEnded = false
        for (engine in engines) {
            val ended = engine.recognizer.acceptWaveForm(buf, n)
            if (engine === primary) {
                sentenceEnded = ended
            } else if (ended) {
                engine.pending += parse(engine.recognizer.result)
            }
        }
        if (!sentenceEnded) return null
        return pick { if (it === primary) it.recognizer.result else it.recognizer.finalResult }
    }

    /** The words heard so far in the sentence being spoken. */
    fun partial(): String = JSONObject(primary.recognizer.partialResult).optString("partial")

    /** Flushes whatever was still being spoken when recording stopped. */
    fun finish(): String? = pick { it.recognizer.finalResult }

    private fun pick(result: (Engine) -> String): String? {
        val candidates = engines.map { engine ->
            val parts = engine.pending + parse(result(engine))
            engine.pending.clear()
            merge(parts)
        }
        var best = candidates.first()
        for (c in candidates.drop(1)) {
            // Small bias towards the primary (Hindi) model: the English model
            // tends to turn Hindi speech into confident-sounding English words.
            if (c.words > 0 && c.confidence > best.confidence + SWITCH_MARGIN) best = c
        }
        return best.text.ifBlank { null }
    }

    private fun parse(json: String): Heard {
        val obj = JSONObject(json)
        val words = obj.optJSONArray("result")
        var sum = 0.0
        val count = words?.length() ?: 0
        for (i in 0 until count) sum += words!!.getJSONObject(i).optDouble("conf", 0.0)
        return Heard(obj.optString("text"), if (count > 0) sum / count else 0.0, count)
    }

    private fun merge(parts: List<Heard>): Heard {
        val words = parts.sumOf { it.words }
        if (words == 0) return Heard("", 0.0, 0)
        return Heard(
            text = parts.map { it.text }.filter { it.isNotBlank() }.joinToString(" "),
            confidence = parts.sumOf { it.confidence * it.words } / words,
            words = words,
        )
    }

    override fun close() {
        engines.forEach {
            it.recognizer.close()
            it.vosk.close()
        }
    }

    companion object {
        private const val SWITCH_MARGIN = 0.05
    }
}
