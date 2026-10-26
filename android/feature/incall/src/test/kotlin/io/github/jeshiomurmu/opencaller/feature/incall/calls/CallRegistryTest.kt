package io.github.jeshiomurmu.opencaller.feature.incall.calls

import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRoute
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AudioRouteType
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A call as the registry sees it; records every command. */
internal class FakeTelecomCall(
    var status: CallStatus = CallStatus.RINGING,
    var handle: String? = "+12015550123",
    var partner: FakeTelecomCall? = null,
) : TelecomCall {
    val commands = mutableListOf<String>()
    var listener: (() -> Unit)? = null
    var failWith: RuntimeException? = null

    override val key: Any get() = this

    override fun snapshot(
        id: String,
        idOf: (Any) -> String?,
    ) = TrackedCall(
        id = id,
        handle = handle,
        hidden = handle == null,
        status = status,
        direction = Direction.INCOMING,
        conferenceableIds = listOfNotNull(partner?.let(idOf)),
    )

    fun change(to: CallStatus) {
        status = to
        listener?.invoke()
    }

    override fun observe(onChange: () -> Unit) {
        listener = onChange
    }

    override fun stopObserving() {
        listener = null
    }

    private fun record(command: String) {
        failWith?.let { throw it }
        commands += command
    }

    override fun answer() = record("answer")

    override fun reject(message: String?) = record("reject:$message")

    override fun disconnect() = record("disconnect")

    override fun hold() = record("hold")

    override fun unhold() = record("unhold")

    override fun playTone(digit: Char) = record("tone:$digit")

    override fun stopTone() = record("stopTone")

    override fun conference(other: TelecomCall) = record("conference")

    override fun mergeConference() = record("mergeConference")

    override fun swapConference() = record("swapConference")

    override fun selectSim(simId: String) = record("sim:$simId")
}

class CallRegistryTest {
    private val registry = CallRegistry()
    private val audio =
        object : AudioControl {
            val routed = mutableListOf<String>()
            var muted: Boolean? = null

            override fun setMuted(muted: Boolean) {
                this.muted = muted
            }

            override fun route(routeId: String) {
                routed += routeId
            }
        }

    @Test
    fun callsGetStableIdsAndChangesArePublished() {
        val call = FakeTelecomCall()
        val id = registry.add(call)
        assertEquals(id, registry.add(call))
        assertEquals(
            CallStatus.RINGING,
            registry.state.value
                .call(id)
                ?.status,
        )

        call.change(CallStatus.ACTIVE)
        assertEquals(
            CallStatus.ACTIVE,
            registry.state.value
                .call(id)
                ?.status,
        )

        val last = registry.remove(call)
        assertEquals(id, last?.id)
        assertTrue(registry.state.value.isIdle)
        assertNull(call.listener)
        assertNull(registry.remove(call))
    }

    @Test
    fun commandsReachTheRightCall() {
        val ringing = FakeTelecomCall()
        val id = registry.add(ringing)
        registry.answer(id)
        registry.declineWithMessage(id, "  Can't talk now ")
        registry.declineWithMessage(id, "   ")
        registry.decline(id)
        registry.playTone(id, '5')
        registry.playTone(id, 'x')
        registry.stopTone(id)
        registry.hold(id)
        registry.resume(id)
        registry.selectSim(id, "1")
        registry.hangUp(id)
        assertEquals(
            listOf(
                "answer",
                "reject:Can't talk now",
                "reject:null",
                "reject:null",
                "tone:5",
                "stopTone",
                "hold",
                "unhold",
                "sim:1",
                "disconnect",
            ),
            ringing.commands,
        )
    }

    @Test
    fun conferenceActionsUseTheTrackedCalls() {
        val held = FakeTelecomCall(CallStatus.HOLDING)
        val active = FakeTelecomCall(CallStatus.ACTIVE, partner = held)
        val heldId = registry.add(held)
        val activeId = registry.add(active)
        assertEquals(
            listOf(heldId),
            registry.state.value
                .call(activeId)
                ?.conferenceableIds,
        )

        registry.perform(ConferenceAction.Merge(activeId, heldId))
        registry.perform(ConferenceAction.MergeConference(activeId))
        registry.perform(ConferenceAction.SwapConference(activeId))
        registry.perform(ConferenceAction.Resume(heldId))
        registry.perform(ConferenceAction.Merge(activeId, "gone"))
        assertEquals(listOf("conference", "mergeConference", "swapConference"), active.commands)
        assertEquals(listOf("unhold"), held.commands)
    }

    @Test
    fun commandsForEndedCallsOrFailingCallsAreDropped() {
        val call = FakeTelecomCall().apply { failWith = IllegalStateException("call already gone") }
        val id = registry.add(call)
        registry.answer(id)
        registry.answer("unknown")
        assertTrue(call.commands.isEmpty())
    }

    @Test
    fun audioGoesThroughTheServiceAndOnlyToOfferedRoutes() {
        registry.setMuted(true)
        assertNull(audio.muted)

        registry.attach(audio)
        registry.updateAudio { it.copy(available = listOf(AudioRoute("bt", AudioRouteType.BLUETOOTH, "Car"))) }
        registry.setMuted(true)
        registry.route("bt")
        registry.route("made-up")
        assertEquals(true, audio.muted)
        assertEquals(listOf("bt"), audio.routed)
        assertEquals(
            "Car",
            registry.state.value.audio.available
                .single()
                .name,
        )
    }

    @Test
    fun theServiceGoingAwayClearsEverything() {
        registry.attach(audio)
        val call = FakeTelecomCall()
        registry.add(call)
        registry.updateCanAddCall(true)
        assertTrue(registry.state.value.canAddCall)

        registry.attach(null)
        assertTrue(
            registry.state.value.calls
                .isEmpty(),
        )
        assertFalse(registry.state.value.canAddCall)
        assertNull(call.listener)
    }
}
