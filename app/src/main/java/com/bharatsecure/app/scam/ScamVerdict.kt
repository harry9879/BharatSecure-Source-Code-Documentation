package com.bharatsecure.app.scam

enum class ScamLevel { SAFE, WARNING, SURE }

enum class ScamType(val id: String, val label: String, val advice: String) {
    PHISHING_OTP(
        "phishing_otp", "Phishing / OTP",
        "Never share an OTP, PIN, password or card number. Don't install apps or open links they send.",
    ),
    DIGITAL_ARREST(
        "digital_arrest", "Digital arrest",
        "Real police never arrest anyone on a video call or ask for money. Hang up and tell your family.",
    ),
    BANKING(
        "banking", "Banking fraud",
        "You never enter a UPI PIN to receive money. Don't accept requests or scan QR codes from callers.",
    ),
    OTHER("other_scam", "Scam", "Don't send money to unknown callers. Hang up and check with family first."),
}

data class ScamVerdict(
    val level: ScamLevel,
    val type: ScamType?,
    /** Scam score from the RAG server, 0..1. */
    val score: Double,
) {
    companion object {
        val SAFE = ScamVerdict(ScamLevel.SAFE, null, 0.0)
    }
}
