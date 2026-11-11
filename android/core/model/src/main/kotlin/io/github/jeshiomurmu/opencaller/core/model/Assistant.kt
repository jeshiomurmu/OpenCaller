package io.github.jeshiomurmu.opencaller.core.model

/** The user's own assistant number (docs/prd.md F-UT-04, ADR 0010): forward unanswered calls to it. */
data class AssistantLine(
    val number: PhoneNumber,
    val country: String,
)

/**
 * Assistant minutes of the calendar month [month] (`yyyy-MM`, UTC): [usedSeconds] of [limitMinutes] (0 = no cap);
 * one call lasts at most [maxCallMinutes].
 */
data class AssistantUsage(
    val month: String,
    val usedSeconds: Int,
    val limitMinutes: Int,
    val maxCallMinutes: Int,
) {
    /** Whole minutes used, rounded up (the meter never shows 0 after a call). */
    val usedMinutes: Int get() = (usedSeconds + SECONDS_PER_MINUTE - 1) / SECONDS_PER_MINUTE

    /** `true` when there is a cap and it is reached. */
    val exhausted: Boolean get() = limitMinutes > 0 && usedSeconds >= limitMinutes * SECONDS_PER_MINUTE

    private companion object {
        const val SECONDS_PER_MINUTE = 60
    }
}

/** A call the assistant answered: the LiveKit room to follow it in, while it lasts. */
data class AssistantCallRoom(
    val callId: String,
    val startedAtMillis: Long,
    val ended: Boolean,
    val room: String,
    val url: String?,
    val token: String?,
)

/** How urgent the caller said the call is. */
enum class CallUrgency {
    LOW,
    NORMAL,
    HIGH,
}

/** What the assistant learned about the caller. */
data class CallerDetails(
    val name: String? = null,
    val reason: String? = null,
    val urgency: CallUrgency = CallUrgency.NORMAL,
    val likelySpam: Boolean = false,
)

/** Who said a line of the conversation. */
enum class Speaker {
    CALLER,
    ASSISTANT,
}

/** One finished line of the conversation. */
data class TranscriptLine(
    val speaker: Speaker,
    val text: String,
)

/** Why a screened call ended. */
enum class ScreeningEnd {
    CALLER_HUNG_UP,
    CALL_BACK,
    SPAM,
    DONE,
    TIME_UP,
    UNKNOWN,
}

/**
 * A screened call kept on the phone only: the caller's number ([caller], `null` when hidden), the details and the
 * transcript came from the agent through the call's room; the server never had them.
 */
data class ScreenedCall(
    val id: String,
    val startedAtMillis: Long,
    val caller: PhoneNumber?,
    val details: CallerDetails,
    val transcript: List<TranscriptLine>,
    val end: ScreeningEnd,
)
