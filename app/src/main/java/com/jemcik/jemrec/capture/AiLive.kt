package com.jemcik.jemrec.capture

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** One line of the live transcript: who said it, and the words. */
data class AiLine(val who: String, val text: String, val at: Long)

/**
 * What the AI call is doing right now, for the live monitor and the call
 * notification. The Termux service sends STATE, TRANSCRIPT and EVENT lines on
 * the AI link; the recorder passes them to the app's monitor socket and
 * [AiLive] keeps them. [up] is the call, not the socket: a reconnect replays
 * the call's lines without making it a new call.
 */
data class AiState(
    val up: Boolean = false,
    /** starting, intro, listening, thinking, speaking, handoff, ended. */
    val phase: String = "",
    /** The call's lines, oldest first, capped for a long call. */
    val transcript: List<AiLine> = emptyList(),
    /** The last EVENT's detail, for the final notice. */
    val endedReason: String? = null,
) {
    val lastCallerLine: String?
        get() = transcript.lastOrNull { it.who == "caller" }?.text

    /** What the notice says the assistant is doing. */
    val phaseWords: String
        get() = when (phase) {
            "starting" -> "Getting ready; your microphone is muted"
            "intro" -> "Saying hello; your microphone is muted"
            "listening" -> "Listening to the caller; your microphone is muted"
            "thinking" -> "Working out a reply; your microphone is muted"
            "speaking" -> "Speaking to the caller; your microphone is muted"
            "handoff" -> "The caller asked for you; still taking a message"
            "unsure" -> "Could not make out the caller; asking again"
            "message" -> "Taking a message for you"
            "ended" -> "The call is over"
            else -> "The assistant is handling this call; your microphone is muted"
        }
}

object AiLive {

    private const val MAX_LINES = 80

    private val _state = MutableStateFlow(AiState())
    val state: StateFlow<AiState> = _state.asStateFlow()

    /** A call became the assistant's: a new transcript starts here. */
    fun begin() {
        _state.value = AiState(up = true, phase = "starting")
    }

    /** The call is over, or the user took it. Lines are kept for the report. */
    fun end() {
        _state.update { it.copy(up = false, phase = "ended") }
    }

    /**
     * A monitor socket opened, and the recorder is about to replay this
     * call's lines. Clearing first means a reconnect shows each line once,
     * not the ones before it twice.
     */
    fun replay() {
        _state.update {
            if (!it.up) it else it.copy(transcript = emptyList())
        }
    }

    /** One control line from the recorder's AI link. */
    fun text(line: String) {
        when {
            line == "END" -> end()
            line == "READY" -> _state.update { if (it.phase == "starting") it.copy(phase = "intro") else it }
            line.startsWith("CALL") -> _state.update { if (it.up) it else it.copy(up = true, phase = "starting") }
            line.startsWith("STATE ") -> {
                val phase = line.removePrefix("STATE ").trim()
                if (phase.isNotEmpty()) _state.update { it.copy(phase = phase) }
            }
            line.startsWith("TRANSCRIPT ") -> {
                val rest = line.removePrefix("TRANSCRIPT ").trim()
                val who = rest.substringBefore(' ')
                val words = rest.substringAfter(' ', "").trim()
                if (words.isEmpty()) return
                _state.update {
                    val lines = (it.transcript + AiLine(who, words, System.currentTimeMillis()))
                    it.copy(transcript = lines.takeLast(MAX_LINES))
                }
            }
            line.startsWith("EVENT ") -> {
                val rest = line.removePrefix("EVENT ").trim()
                val name = rest.substringBefore(' ')
                val detail = rest.substringAfter(' ', "").trim()
                _state.update {
                    when (name) {
                        "handoff" -> it.copy(phase = "handoff", endedReason = detail.ifBlank { "asked for you" })
                        "unsure", "message" -> it.copy(phase = name)
                        "hungup", "limit", "silence", "ended" ->
                            it.copy(endedReason = detail.ifBlank { name })
                        else -> it
                    }
                }
            }
        }
    }

    /** A new window reading a call that is already over: the last call's
     *  transcript stays, but is no longer live. */
    fun forget() {
        _state.value = AiState()
    }
}
