package com.bharatsecure.app.scam

/**
 * Digital-arrest rules and the OTP-request rule. Not currently used by the app.
 *
 * They look for the classic script's building blocks in phone-style text and
 * can only RAISE the verdict, never lower it. One block alone is never
 * enough: real police calls mention police, real courier calls mention parcels.
 */
object ScamRules {
    private val SIGNALS = mapOf(
        "authority" to Regex(
            "\\b(?:cbi|ncb|enforcement directorate|narcotics|crime branch|cyber (?:crime|cell)|customs|" +
                "trai|supreme court|high court|dcp|ips officer|police (?:station|officer|headquarters)|" +
                "income tax department|rbi officer)\\b"
        ),
        "accusation" to Regex(
            "\\b(?:money laundering|hawala|drugs?|mdma|narcotic|terror(?:ist)? funding|illegal|" +
                "fake passports?|black money|human trafficking|seized|parcel|courier|fedex)\\b"
        ),
        "threat" to Regex(
            "\\b(?:arrest(?:ed)?|giraftar\\w*|warrant|fir|non bailable|jail|case (?:registered|darj)|" +
                "digital arrest)\\b"
        ),
        "isolation" to Regex(
            "\\b(?:video call|camera on|skype|stay on (?:the )?(?:call|line|video)|(?:do not|dont) " +
                "(?:disconnect|cut|end) the call|call mat kaat\\w*|phone mat rakh\\w*|(?:do not|dont) tell " +
                "(?:anyone|anybody|your family)|kisi ko (?:mat|nahi) bata\\w*|ghar walon ko mat)\\b"
        ),
        "payment" to Regex(
            "\\b(?:safe account|secure account|verification (?:account|amount|transfer)|security deposit|" +
                "clear(?:ance)? (?:your name)?|settlement|escrow|government account|rbi (?:account|verification)|" +
                "break (?:your )?fixed deposit|transfer (?:your|all) (?:savings|money))\\b"
        ),
    )

    fun signals(phoneText: String): Set<String> =
        SIGNALS.filterValues { it.containsMatchIn(phoneText) }.keys

    /** SURE, WARNING, or null when the script isn't convincingly present. */
    fun level(phoneText: String): ScamLevel? {
        val found = signals(phoneText)
        if ("authority" !in found && "accusation" !in found) return null
        val core = found intersect setOf("threat", "isolation", "payment")
        return when {
            core.size >= 2 || (found.size >= 4 && core.isNotEmpty()) -> ScamLevel.SURE
            found.size >= 3 && core.isNotEmpty() -> ScamLevel.WARNING
            else -> null
        }
    }

    // --- OTP / PIN request: the caller asks for the code to be given TO THEM.
    // "share the otp with the delivery person" and "never share your otp" are skipped.
    private const val SECRET = """(?:otp|one time password|o t p|pin|upi pin|atm pin|cvv|verification code|security code|code)"""
    private const val SECRET_OR_PASSWORD = """(?:otp|one time password|o t p|pin|upi pin|atm pin|cvv|password|verification code|security code|code)"""
    private const val VERB = """(?:tell|send|share|give|read|say|forward|repeat|confirm|provide|bata\w*|bhej\w*|de do|dedo|dijiye|bol\w*)"""
    private const val TO_ME = """(?:me|us|to me|with me|to us|with us|on (?:this|the) call|over the phone|on phone|mujhe|hume|humein|hamein)"""
    private const val NOT_MORE = """(?! (?:more|about|what|how|why|if|whether|the procedure|the process))"""
    private val OTP_REQUEST = Regex(listOf(
        """\b${VERB} (?:${TO_ME} )(?:\w+ ){0,5}${SECRET_OR_PASSWORD}\b""",
        """\b${SECRET}\b(?: \w+){0,8} ${VERB} (?:it |that |this )?${TO_ME}\b${NOT_MORE}""",
        """\b${SECRET}\b(?: \w+){0,10} (?:can|could|will) you (?:please )?${VERB} (?:it )?${TO_ME}\b${NOT_MORE}""",
        """\b${SECRET} (?:\w+ ){0,2}(?:bata|bhej|bol|daal)(?:o|iye|ein|en| do| dijiye| dena| de)\b""",
    ).joinToString("|"))
    private val OTP_SAFE_CONTEXT = Regex(
        """\b(?:delivery (?:person|agent|partner|boy|executive)|never|not|dont|do not|mat|nahi|kabhi|no one|nobody)\b"""
    )

    /** True if the caller asks for an OTP/PIN/code to be given to them (raises to WARNING at most). */
    fun otpRequest(phoneText: String): Boolean =
        OTP_REQUEST.findAll(phoneText).any { m ->
            val from = maxOf(0, m.range.first - 40)
            val to = minOf(phoneText.length, m.range.last + 1 + 40)
            !OTP_SAFE_CONTEXT.containsMatchIn(phoneText.substring(from, to))
        }
}
