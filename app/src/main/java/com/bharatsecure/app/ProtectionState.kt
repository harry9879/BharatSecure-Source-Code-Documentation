package com.bharatsecure.app

import com.bharatsecure.app.scam.ScamVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class CallSource(val label: String) {
    PHONE("Phone call"),
    WHATSAPP("WhatsApp call"),
    MANUAL("Manual"),
}

data class ProtectionUiState(
    val running: Boolean = false,
    val source: CallSource? = null,
    val caller: String? = null,
    val startedAtMs: Long = 0L,
    /** Finished sentences from the recognizer. */
    val transcript: List<String> = emptyList(),
    /** The sentence currently being spoken; replaced on every update. */
    val partial: String = "",
    /** 0..1 microphone loudness, so the user can see whether audio is coming through. */
    val level: Float = 0f,
    /** Highest scam verdict reached so far in this call (never goes back down). */
    val verdict: ScamVerdict = ScamVerdict.SAFE,
    /** Latest scam score (1 - P(safe)) for the whole call so far, 0..1. */
    val scamScore: Double = 0.0,
    /** Laptop RAG server: "" when not used, otherwise "Connected" / "Laptop offline: …". */
    val ragStatus: String = "",
    /** Latest verdict from the laptop RAG server, null until the first reply. */
    val rag: RagUpdate? = null,
    val status: String = "",
)

/** In-process state shared between the recording service and the UI. */
object ProtectionState {
    private val _state = MutableStateFlow(ProtectionUiState())
    val state: StateFlow<ProtectionUiState> = _state

    fun update(block: (ProtectionUiState) -> ProtectionUiState) = _state.update(block)
}
