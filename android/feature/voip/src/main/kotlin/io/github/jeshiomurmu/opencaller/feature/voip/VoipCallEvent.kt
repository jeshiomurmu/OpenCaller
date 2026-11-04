package io.github.jeshiomurmu.opencaller.feature.voip

/** What the user does on the app-call screen. */
sealed interface VoipCallEvent {
    /** Answer the ringing call. */
    data object Answer : VoipCallEvent

    /** Decline, cancel or hang up. */
    data object HangUp : VoipCallEvent

    /** Mute or unmute. */
    data class Mute(
        val muted: Boolean,
    ) : VoipCallEvent

    /** Speaker on or off. */
    data class Speaker(
        val on: Boolean,
    ) : VoipCallEvent

    /** Ask for the microphone again. */
    data object AllowMicrophone : VoipCallEvent
}
