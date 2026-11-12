package io.github.jeshiomurmu.opencaller.feature.assistant

import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber

/** One conditional call-forwarding code to dial, and what it does. */
data class ForwardingCode(
    val kind: ForwardingKind,
    val code: String,
)

/** The standard GSM conditions (3GPP TS 22.030 supplementary-service codes). */
enum class ForwardingKind {
    /** `**61*`: when the user doesn't answer. */
    NO_REPLY,

    /** `**62*`: when the phone is off or out of coverage. */
    UNREACHABLE,

    /** `**67*`: when the user is on another call. */
    BUSY,
}

/**
 * The MMI codes that send unanswered calls to the assistant number (docs/prd.md F-UT-04). They are the standard GSM
 * codes; many carriers accept them, some don't (prepaid, some VoLTE networks) and some use their own: the setup
 * screen says so and offers the carrier's own way instead.
 */
object ForwardingCodes {
    /** Cancels every call forwarding at once (`##002#`). */
    const val CANCEL_ALL = "##002#"

    /** The three codes for [number], in the order the setup offers them. */
    fun forNumber(number: PhoneNumber): List<ForwardingCode> =
        listOf(
            ForwardingCode(ForwardingKind.NO_REPLY, "**61*${number.e164}#"),
            ForwardingCode(ForwardingKind.UNREACHABLE, "**62*${number.e164}#"),
            ForwardingCode(ForwardingKind.BUSY, "**67*${number.e164}#"),
        )
}
