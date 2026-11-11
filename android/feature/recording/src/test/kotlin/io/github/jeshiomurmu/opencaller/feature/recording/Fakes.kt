package io.github.jeshiomurmu.opencaller.feature.recording

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.recording.CallRecordings
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RecordingLine
import io.github.jeshiomurmu.opencaller.core.model.RecordingSession
import io.github.jeshiomurmu.opencaller.core.model.RecordingSessionState
import io.github.jeshiomurmu.opencaller.core.model.SavedRecording
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallController
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStatus
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ConferenceAction
import io.github.jeshiomurmu.opencaller.core.telephony.incall.InCallState
import io.github.jeshiomurmu.opencaller.core.telephony.incall.TrackedCall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

val LINE = RecordingLine("0f8fad5b-d9cb-469f-a165-70867728950e", PhoneNumber("+8809638000000"), "12345678", 0)
val OTHER = PhoneNumber("+8801711000001")

fun call(
    id: String,
    status: CallStatus,
    handle: String,
    direction: Direction = Direction.OUTGOING,
) = TrackedCall(id, handle, hidden = false, status = status, direction = direction)

/** Telecom as the recorder sees it: placing a call adds it; merging puts both calls into conference "c". */
class FakeTelecom :
    CallStateRepository,
    CallController,
    CallPlacer {
    val calls = MutableStateFlow(InCallState())
    val commands = mutableListOf<String>()
    var placeResult: AppResult<Unit> = AppResult.Success(Unit)
    var mergeable = true

    override val state: StateFlow<InCallState> = calls

    fun update(transform: (TrackedCall) -> TrackedCall) {
        calls.value = calls.value.copy(calls = calls.value.calls.map(transform))
    }

    override fun place(
        number: String,
        simId: String?,
    ): AppResult<Unit> {
        commands += "place $number"
        if (placeResult is AppResult.Success) {
            val line = call("2", CallStatus.DIALING, number.substringBefore(','))
            calls.value = calls.value.copy(calls = calls.value.calls.map { it.copy(status = CallStatus.HOLDING) } + line)
        }
        return placeResult
    }

    /** The line answers: the user's call stays on hold and can be merged with it. */
    fun lineAnswers() =
        update {
            if (it.id == "2") it.copy(status = CallStatus.ACTIVE, conferenceableIds = if (mergeable) listOf("1") else emptyList()) else it
        }

    override fun perform(action: ConferenceAction) {
        commands += "perform $action"
        if (action is ConferenceAction.Merge) {
            val conference = TrackedCall("c", null, hidden = false, status = CallStatus.ACTIVE, direction = null, isConference = true)
            calls.value =
                calls.value.copy(calls = calls.value.calls.map { it.copy(parentId = "c", status = CallStatus.ACTIVE) } + conference)
        }
    }

    override fun hangUp(callId: String) {
        commands += "hang up $callId"
        update { if (it.id == callId) it.copy(status = CallStatus.DISCONNECTED) else it }
    }

    override fun answer(callId: String) = Unit

    override fun decline(callId: String) = Unit

    override fun declineWithMessage(
        callId: String,
        message: String,
    ) = Unit

    override fun hold(callId: String) = Unit

    override fun resume(callId: String) = Unit

    override fun playTone(
        callId: String,
        digit: Char,
    ) = Unit

    override fun stopTone(callId: String) = Unit

    override fun setMuted(muted: Boolean) = Unit

    override fun route(routeId: String) = Unit

    override fun selectSim(
        callId: String,
        simId: String,
    ) = Unit

    override fun refresh() = Unit

    override fun canPlaceCalls() = true

    override fun isEmergencyNumber(number: String) = false

    override fun placeEmergency(number: String): AppResult<Unit> = AppResult.Success(Unit)

    override fun placeVoicemail(simId: String?): AppResult<Unit> = AppResult.Success(Unit)

    override fun sendSecretCode(digits: String): AppResult<Unit> = AppResult.Success(Unit)

    override fun handOff(number: String): AppResult<Unit> = AppResult.Success(Unit)
}

/** The server's side: [sessionStates] answer one session check each (the last one repeats). */
class FakeRecordings : CallRecordings {
    var line: AppResult<RecordingLine> = AppResult.Success(LINE)
    val sessionStates = ArrayDeque(listOf(RecordingSession(RecordingSessionState.PENDING)))
    val savedCalls = mutableListOf<Pair<String, PhoneNumber?>>()
    val deleted = mutableListOf<String>()
    var transcriptsOn = false
    var syncResult: AppResult<Int> = AppResult.Success(0)
    override val saved = MutableStateFlow<List<SavedRecording>>(emptyList())

    override suspend fun startLine() = line

    override suspend fun session(sessionId: String): AppResult<RecordingSession> =
        AppResult.Success(if (sessionStates.size > 1) sessionStates.removeFirst() else sessionStates.first())

    override suspend fun save(
        recordingId: String,
        otherParty: PhoneNumber?,
    ): AppResult<SavedRecording> {
        savedCalls += recordingId to otherParty
        return AppResult.Success(SavedRecording(recordingId, otherParty, "/r/$recordingId.ogg", 1_000, null, 0))
    }

    override suspend fun syncAll() = syncResult

    override suspend fun delete(id: String): AppResult<Unit> {
        deleted += id
        saved.value = saved.value.filterNot { it.id == id }
        return AppResult.Success(Unit)
    }

    override suspend fun transcripts(): AppResult<Boolean> = AppResult.Success(transcriptsOn)

    override suspend fun setTranscripts(enabled: Boolean): AppResult<Boolean> {
        if (enabled && line is AppResult.Failure) return AppResult.Failure(AppError.Network)
        transcriptsOn = enabled
        return AppResult.Success(enabled)
    }
}
