package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which call the in-call screen puts first, and TC-INCALL-002's merge and swap decisions. */
class InCallStateTest {
    private fun call(
        id: String,
        status: CallStatus,
        vararg capabilities: CallCapability,
        conferenceable: List<String> = emptyList(),
        conference: Boolean = false,
        parent: String? = null,
    ) = TrackedCall(
        id = id,
        handle = "+1201555010$id",
        hidden = false,
        status = status,
        direction = Direction.OUTGOING,
        capabilities = capabilities.toSet(),
        isConference = conference,
        parentId = parent,
        conferenceableIds = conferenceable,
    )

    @Test
    fun theCallInProgressComesFirstAndARingingCallIsSeparate() {
        val state =
            InCallState(
                listOf(
                    call("1", CallStatus.HOLDING, CallCapability.HOLD),
                    call("2", CallStatus.ACTIVE),
                    call("3", CallStatus.RINGING),
                ),
            )
        assertEquals("2", state.primary?.id)
        assertEquals("1", state.held?.id)
        assertEquals("3", state.ringing?.id)
        assertFalse(state.isIdle)
    }

    @Test
    fun aRingingCallAloneHasNoPrimaryAndEndedCallsDontCount() {
        val ringing = InCallState(listOf(call("1", CallStatus.RINGING), call("2", CallStatus.DISCONNECTED)))
        assertNull(ringing.primary)
        assertEquals(listOf("1"), ringing.topLevel.map(TrackedCall::id))
        assertTrue(InCallState(listOf(call("2", CallStatus.DISCONNECTED))).isIdle)
        assertTrue(InCallState().isIdle)
    }

    @Test
    fun callsInsideAConferenceAreNotRowsOfTheirOwn() {
        val state =
            InCallState(
                listOf(
                    call("c", CallStatus.ACTIVE, conference = true),
                    call("1", CallStatus.ACTIVE, parent = "c"),
                    call("2", CallStatus.ACTIVE, parent = "c"),
                ),
            )
        assertEquals(listOf("c"), state.topLevel.map(TrackedCall::id))
        assertEquals("c", state.primary?.id)
    }

    @Test
    fun twoSeparateCallsMergeIntoAConference() {
        val state =
            InCallState(
                listOf(call("1", CallStatus.HOLDING, CallCapability.HOLD), call("2", CallStatus.ACTIVE, conferenceable = listOf("3", "1"))),
            )
        // "3" isn't a live call; the held call is preferred anyway.
        assertEquals(ConferenceAction.Merge("2", "1"), state.mergeAction())
    }

    @Test
    fun aConferenceThatOffersMergingMergesItsCalls() {
        val state = InCallState(listOf(call("c", CallStatus.ACTIVE, CallCapability.MERGE_CONFERENCE, conference = true)))
        assertEquals(ConferenceAction.MergeConference("c"), state.mergeAction())
    }

    @Test
    fun nothingToMergeWithoutAnActiveCallOrPartner() {
        assertNull(InCallState(listOf(call("1", CallStatus.ACTIVE))).mergeAction())
        assertNull(
            InCallState(listOf(call("1", CallStatus.DIALING, conferenceable = listOf("2")), call("2", CallStatus.HOLDING))).mergeAction(),
        )
    }

    @Test
    fun swappingResumesTheHeldCall() {
        val state =
            InCallState(listOf(call("1", CallStatus.ACTIVE, CallCapability.HOLD), call("2", CallStatus.HOLDING, CallCapability.HOLD)))
        assertEquals(ConferenceAction.Resume("2"), state.swapAction())
    }

    @Test
    fun aConferenceThatOffersSwappingSwapsItsCalls() {
        val state = InCallState(listOf(call("c", CallStatus.ACTIVE, CallCapability.SWAP_CONFERENCE, conference = true)))
        assertEquals(ConferenceAction.SwapConference("c"), state.swapAction())
    }

    @Test
    fun nothingToSwapWithOneCallOrAHeldCallThatCantResume() {
        assertNull(InCallState(listOf(call("1", CallStatus.ACTIVE, CallCapability.HOLD))).swapAction())
        assertNull(InCallState(listOf(call("1", CallStatus.ACTIVE), call("2", CallStatus.HOLDING))).swapAction())
        assertNull(InCallState().swapAction())
    }

    @Test
    fun headsetRoutesCountAsExternal() {
        val phoneOnly = CallAudio(available = listOf(AudioRoute("e", AudioRouteType.EARPIECE), AudioRoute("s", AudioRouteType.SPEAKER)))
        assertFalse(phoneOnly.hasExternalRoutes)
        assertTrue(phoneOnly.copy(available = phoneOnly.available + AudioRoute("b", AudioRouteType.BLUETOOTH, "Car")).hasExternalRoutes)
    }
}
