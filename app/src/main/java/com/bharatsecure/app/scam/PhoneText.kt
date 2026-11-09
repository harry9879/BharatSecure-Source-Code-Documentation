package com.bharatsecure.app.scam

/**
 * Cleanup to a plain Roman-letter form: lowercase words, no punctuation,
 * no stage directions.
 */
object PhoneText {
    private val STAGE_DIRECTIONS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\*[^*]*\\*")
    private val RUPEE = Regex("₹\\s*")
    private val NON_WORD = Regex("[^a-z0-9\\s]")
    private val SPACES = Regex("\\s+")

    fun clean(text: String): String {
        var t = STAGE_DIRECTIONS.replace(text, " ")
        t = RUPEE.replace(t, " rupees ")
        t = t.lowercase().replace("'", "")
        t = NON_WORD.replace(t, " ")
        return SPACES.replace(t, " ").trim()
    }

    /** App transcript (Devanagari and/or Roman) to model input. */
    fun fromTranscript(text: String): String = clean(Transliterator.toRoman(text))
}
