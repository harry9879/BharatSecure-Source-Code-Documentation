package com.bharatsecure.app.scam

/**
 * Converts Devanagari (what the Hindi speech model writes) into Roman
 * Hinglish (what the scam model was trained on):
 *   "हम आपको अरेस्ट कर रहे हैं"  ->  "hum aapko arrest kar rahe hain"
 *
 * Two parts:
 *  - English words that the recognizer wrote in Hindi script (अरेस्ट, ओटीपी,
 *    पुलिस …) come from a dictionary, so they match the model's English
 *    keywords exactly;
 *  - everything else is converted letter by letter, dropping the silent
 *    inherent "a" the way Hindi speakers do (आपको -> aapko, not aapako).
 *
 * Roman text passes through unchanged, so mixed lines are fine.
 */
object Transliterator {

    /** English words as Vosk spells them in Devanagari. */
    private val LOANWORDS = mapOf(
        "अरेस्ट" to "arrest", "अरेस्टेड" to "arrested", "पुलिस" to "police", "ऑफिसर" to "officer",
        "ओटीपी" to "otp", "पिन" to "pin", "पासवर्ड" to "password", "अकाउंट" to "account", "एकाउंट" to "account",
        "बैंक" to "bank", "पेमेंट" to "payment", "लिंक" to "link", "कस्टम्स" to "customs", "कस्टम" to "customs",
        "पार्सल" to "parcel", "कूरियर" to "courier", "केस" to "case", "वीडियो" to "video", "कॉल" to "call",
        "आधार" to "aadhaar", "केवाईसी" to "kyc", "यूपीआई" to "upi", "रिफंड" to "refund", "लोन" to "loan",
        "सीबीआई" to "cbi", "ड्रग्स" to "drugs", "ड्रग" to "drugs", "मनी" to "money", "लॉन्ड्रिंग" to "laundering",
        "डिजिटल" to "digital", "डीजल" to "digital", "वारंट" to "warrant", "कोर्ट" to "court", "एफआईआर" to "fir",
        "साइबर" to "cyber", "क्राइम" to "crime", "टैक्स" to "tax", "इनकम" to "income", "डिपार्टमेंट" to "department",
        "कार्ड" to "card", "क्रेडिट" to "credit", "डेबिट" to "debit", "नंबर" to "number", "मोबाइल" to "mobile",
        "फोन" to "phone", "फ़ोन" to "phone", "मैसेज" to "message", "कोड" to "code", "ऐप" to "app", "एप" to "app",
        "डाउनलोड" to "download", "इंस्टॉल" to "install", "क्लिक" to "click", "सर" to "sir", "मैडम" to "madam",
        "सिम" to "sim", "कंपनी" to "company", "ट्रांसफर" to "transfer", "अमाउंट" to "amount", "फीस" to "fee",
        "चार्ज" to "charge", "जॉब" to "job", "लॉटरी" to "lottery", "कैशबैक" to "cashback", "ऑफर" to "offer",
        "ब्लॉक" to "block", "वेरिफिकेशन" to "verification", "वेरीफाई" to "verify", "अपडेट" to "update",
        "डिटेल्स" to "details", "सिक्योरिटी" to "security", "सेफ" to "safe", "आरबीआई" to "rbi", "एसबीआई" to "sbi",
        "पेटीएम" to "paytm", "फोनपे" to "phonepe", "गूगल" to "google", "पे" to "pay", "क्यूआर" to "qr",
        "स्कैन" to "scan", "रुपये" to "rupees", "रुपए" to "rupees", "लाख" to "lakh", "हजार" to "hazaar",
        "हज़ार" to "hazaar", "प्लीज" to "please", "प्लीज़" to "please", "ओके" to "ok", "थैंक" to "thank",
        "यू" to "you", "हेलो" to "hello", "हैलो" to "hello", "इंस्पेक्टर" to "inspector", "ईडी" to "ed",
        "नारकोटिक्स" to "narcotics", "कैमरा" to "camera", "स्काइप" to "skype", "सेटलमेंट" to "settlement",
        // Everyday Hindi words, spelled the way the training text spells them.
        "हम" to "hum", "तुम" to "tum", "है" to "hai", "हैं" to "hain", "में" to "mein", "नहीं" to "nahi",
        "मैं" to "main", "क्या" to "kya", "यह" to "ye", "ये" to "ye", "वह" to "woh", "वो" to "woh", "और" to "aur",
        "कि" to "ki", "की" to "ki", "को" to "ko", "से" to "se", "का" to "ka", "के" to "ke", "भी" to "bhi",
        "जी" to "ji", "आप" to "aap", "आपका" to "aapka", "आपको" to "aapko", "आपके" to "aapke", "आपकी" to "aapki",
        "अभी" to "abhi", "हाँ" to "haan", "हां" to "haan", "ठीक" to "theek", "मत" to "mat", "पैसे" to "paise",
        "पैसा" to "paisa", "किसी" to "kisi", "कोई" to "koi", "दीजिए" to "dijiye", "कीजिए" to "kijiye",
        "बताइए" to "bataiye", "बताइये" to "bataiye", "हूँ" to "hoon", "हूं" to "hoon", "घर" to "ghar",
        "वालों" to "walon", "बताना" to "batana", "जल्दी" to "jaldi", "तुरंत" to "turant",
    )

    private val INDEPENDENT_VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo", 'ऋ' to "ri",
        'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o", 'ऍ' to "e",
    )
    private val MATRAS = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "i", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॉ' to "o", 'ॅ' to "e",
    )
    private val CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v", 'श' to "sh",
        'ष' to "sh", 'स' to "s", 'ह' to "h", 'ळ' to "l",
    )
    // Consonant + nukta (़): borrowed sounds.
    private val NUKTA = mapOf('क' to "q", 'ख' to "kh", 'ग' to "gh", 'ज' to "z", 'ड' to "r", 'ढ' to "rh", 'फ' to "f", 'य' to "y")
    private const val NUKTA_SIGN = '़'
    private const val VIRAMA = '्'
    private const val ANUSVARA = 'ं'
    private const val CHANDRABINDU = 'ँ'
    private const val VISARGA = 'ः'

    private val WORD = Regex("[\\u0900-\\u097F]+")

    /** Converts every Devanagari word in [text]; other text is left as it is. */
    fun toRoman(text: String): String = WORD.replace(text) { m -> word(m.value) }

    /** One Devanagari word. */
    fun word(w: String): String {
        LOANWORDS[w]?.let { return it }
        // Syllables: (consonant sound or vowel sound, has inherent "a"?)
        data class Unit(val sound: String, var schwa: Boolean, val isVowel: Boolean)
        val units = mutableListOf<Unit>()
        var i = 0
        while (i < w.length) {
            val c = w[i]
            when {
                c in CONSONANTS -> {
                    var sound = CONSONANTS.getValue(c)
                    if (i + 1 < w.length && w[i + 1] == NUKTA_SIGN) {
                        sound = NUKTA[c] ?: sound
                        i++
                    }
                    val next = w.getOrNull(i + 1)
                    when {
                        next == VIRAMA -> { units += Unit(sound, false, false); i++ }
                        next != null && next in MATRAS -> {
                            units += Unit(sound, false, false)
                            units += Unit(MATRAS.getValue(next), false, true)
                            i++
                        }
                        else -> units += Unit(sound, true, false)
                    }
                }
                c in INDEPENDENT_VOWELS -> units += Unit(INDEPENDENT_VOWELS.getValue(c), false, true)
                c == ANUSVARA || c == CHANDRABINDU -> units += Unit("n", false, false)
                c == VISARGA -> units += Unit("h", false, false)
                c in '०'..'९' -> units += Unit(('0' + (c - '०')).toString(), false, false)
                // Other marks (danda, avagraha …) are dropped.
            }
            i++
        }
        val kept = dropSchwas(units.map { it.schwa }.toBooleanArray(), units.map { it.isVowel })
        units.forEachIndexed { k, u -> u.schwa = kept[k] }

        val out = StringBuilder()
        units.forEachIndexed { k, u ->
            val last = k == units.lastIndex
            // Word-final long vowels are written short in everyday Hinglish
            // (रहा -> raha, नहीं -> nahin, भी -> bhi).
            out.append(if (last && u.sound == "aa" && units.size > 1) "a" else u.sound)
            if (u.schwa) out.append('a')
        }
        return out.toString()
    }

    /**
     * Hindi schwa deletion: the inherent "a" is silent at the end of a word,
     * and in the middle when a vowel sound comes before the consonant and a
     * consonant + vowel comes after it (आपको: aa-p(a)-ko -> aapko,
     * करना: ka-r(a)-naa -> karnaa). Scans right to left so each deletion is
     * seen by the next decision.
     */
    private fun dropSchwas(schwa: BooleanArray, isVowel: List<Boolean>): BooleanArray {
        val keep = schwa.copyOf()
        val n = keep.size
        if (n == 0) return keep
        // Last consonant of a word of 2+ sounds loses its "a".
        if (n > 1 && keep[n - 1]) keep[n - 1] = false
        fun vowelAt(k: Int) = k in 0 until n && (isVowel[k] || keep[k])
        for (k in n - 2 downTo 1) {
            if (!keep[k]) continue
            val before = vowelAt(k - 1)
            val nextIsConsonant = k + 1 < n && !isVowel[k + 1]
            val thenVowel = vowelAt(k + 1) || (k + 2 < n && isVowel[k + 2])
            if (before && nextIsConsonant && thenVowel) keep[k] = false
        }
        return keep
    }
}
