package io.github.jeshiomurmu.opencaller.core.model

/**
 * A phrase scam calls use that the voice scan heard the other party say (docs/prd.md F-PR-13, ADR 0011). The
 * server sends only these ids; the transcript never leaves the scanner. [wire] is the id on the wire.
 */
enum class ScamCue(
    val wire: String,
) {
    OTP_REQUEST("otp_request"),
    PAYMENT_REQUEST("payment_request"),
    GIFT_CARD("gift_card"),
    REMOTE_ACCESS("remote_access"),
    URGENCY("urgency"),
    AUTHORITY("authority"),
    SECRECY("secrecy"),
    PRIZE("prize"),
    ACCOUNT_PROBLEM("account_problem"),
    PERSONAL_DATA("personal_data"),
    ;

    /** Lookup by wire id. */
    companion object {
        /** The cue with [wire] id, or null for one this version doesn't know. */
        fun of(wire: String): ScamCue? = entries.firstOrNull { it.wire == wire }
    }
}

/** How the voice scan reads the model's score: a likelihood, never a verdict (the explanation always says so). */
enum class VoiceVerdict {
    /** The model found the voice more likely a person's than generated. */
    LIKELY_HUMAN,

    /** The model found the voice more likely generated (text-to-speech or a cloned voice). */
    POSSIBLY_AI,
}

/**
 * A finished voice scan: the anti-spoofing model's [spoofProbability] (0–1) for the other party's voice, and the
 * scam [cues] heard in what they said.
 */
data class VoiceScanResult(
    val spoofProbability: Float,
    val cues: List<ScamCue>,
) {
    /** "Possibly AI-generated" from [AI_THRESHOLD] up, "Likely human" below it. */
    val verdict: VoiceVerdict
        get() = if (spoofProbability >= AI_THRESHOLD) VoiceVerdict.POSSIBLY_AI else VoiceVerdict.LIKELY_HUMAN

    /** The threshold. */
    companion object {
        /** The model's two classes meet here; its score isn't calibrated (ml/voice-spoof/README.md). */
        const val AI_THRESHOLD = 0.5f
    }
}

/** A voice scan session's state on the server and, once it [ended][RecordingSessionState.ENDED], its result. */
data class VoiceScanSession(
    val state: RecordingSessionState,
    val result: VoiceScanResult? = null,
)
