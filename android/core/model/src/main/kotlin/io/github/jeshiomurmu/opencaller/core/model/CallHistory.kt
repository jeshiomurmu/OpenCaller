package io.github.jeshiomurmu.opencaller.core.model

/** How a call in the device's call log went (`CallLog.Calls.TYPE`). */
enum class CallType {
    /** Answered incoming call. */
    INCOMING,

    /** Call the user placed. */
    OUTGOING,

    /** Incoming call nobody answered. */
    MISSED,

    /** Incoming call the user declined. */
    REJECTED,

    /** Incoming call blocked automatically (by OpenCaller or Android). */
    BLOCKED,

    /** A voicemail. */
    VOICEMAIL,

    /** Answered on another device. */
    ANSWERED_ELSEWHERE,

    /** A type this version doesn't know. */
    UNKNOWN,
}

/**
 * One call from the device's call log. The log stays in Android's call log provider: OpenCaller reads it on the device
 * and never uploads it (docs/architecture.md §10).
 *
 * @property number the caller or callee in E.164, `null` for a hidden caller or a handle that isn't a phone number.
 * @property dialString what Android logged, shown and redialled when [number] is `null` (short codes, `*123#`); empty
 *   for a hidden caller.
 * @property cachedName the name Android stored with the call, if any.
 * @property simId the SIM (phone account id) the call used, when known.
 */
data class CallLogEntry(
    val id: Long,
    val number: PhoneNumber?,
    val dialString: String,
    val cachedName: String?,
    val type: CallType,
    val atMillis: Long,
    val durationSeconds: Long,
    val simId: String? = null,
) {
    /** True when the caller hid their number. */
    val isHidden: Boolean get() = number == null && dialString.isBlank()

    /** What to dial to call back: the E.164 number, else what was logged, `null` for a hidden caller. */
    val callBackString: String? get() = number?.e164 ?: dialString.takeIf(String::isNotBlank)
}
