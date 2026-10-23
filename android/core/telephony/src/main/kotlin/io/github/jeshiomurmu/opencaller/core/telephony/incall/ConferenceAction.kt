package io.github.jeshiomurmu.opencaller.core.telephony.incall

/** A merge or swap of calls (`android.telecom.Call`'s conference methods). */
sealed interface ConferenceAction {
    /** Joins [callId] and [otherId] into a conference (`Call.conference`). */
    data class Merge(
        val callId: String,
        val otherId: String,
    ) : ConferenceAction

    /** Merges the calls of the conference [callId] (`Call.mergeConference`). */
    data class MergeConference(
        val callId: String,
    ) : ConferenceAction

    /** Swaps the calls of the conference [callId] (`Call.swapConference`). */
    data class SwapConference(
        val callId: String,
    ) : ConferenceAction

    /** Swaps two separate calls by taking [heldId] off hold; Telecom holds the active call first. */
    data class Resume(
        val heldId: String,
    ) : ConferenceAction
}

/**
 * How to merge the calls in [this] state, or `null` when they can't be merged: the call in progress joins a call it is
 * conferenceable with (preferring the one on hold), or a conference that offers merging merges its calls.
 */
fun InCallState.mergeAction(): ConferenceAction? {
    val primary = primary?.takeIf { it.status == CallStatus.ACTIVE } ?: return null
    val live = calls.filter(TrackedCall::isLive).map(TrackedCall::id).toSet()
    val candidates = primary.conferenceableIds.filter { it in live }
    val other = candidates.firstOrNull { it == held?.id } ?: candidates.firstOrNull()
    return when {
        other != null -> ConferenceAction.Merge(primary.id, other)
        primary.isConference && primary.can(CallCapability.MERGE_CONFERENCE) -> ConferenceAction.MergeConference(primary.id)
        else -> null
    }
}

/**
 * How to swap between calls, or `null` when there is nothing to swap: a conference that offers swapping swaps its
 * calls; otherwise, with one call in progress and another on hold, the held call is taken off hold.
 */
fun InCallState.swapAction(): ConferenceAction? {
    val primary = primary ?: return null
    val heldCall = held
    return when {
        primary.isConference && primary.can(CallCapability.SWAP_CONFERENCE) -> ConferenceAction.SwapConference(primary.id)
        heldCall != null && primary.status == CallStatus.ACTIVE && heldCall.can(CallCapability.HOLD) -> ConferenceAction.Resume(heldCall.id)
        else -> null
    }
}
