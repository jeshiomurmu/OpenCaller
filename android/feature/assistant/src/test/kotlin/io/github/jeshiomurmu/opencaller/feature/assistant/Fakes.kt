package io.github.jeshiomurmu.opencaller.feature.assistant

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomClient
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomSession
import io.github.jeshiomurmu.opencaller.core.data.assistant.CallAssistant
import io.github.jeshiomurmu.opencaller.core.model.AssistantCallRoom
import io.github.jeshiomurmu.opencaller.core.model.AssistantLine
import io.github.jeshiomurmu.opencaller.core.model.AssistantUsage
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

val NUMBER = PhoneNumber("+8809638000001")
const val CALL_ID = "7b0a0c7e-1f7c-4d73-9d0e-2cbe0e6f6a10"

class FakeAssistant : CallAssistant {
    var line: AssistantLine? = null
    var allocateResult: AppResult<AssistantLine> = AppResult.Success(AssistantLine(NUMBER, "BD"))
    var usageResult: AppResult<AssistantUsage> = AppResult.Success(AssistantUsage("2026-11", 61, 100, 5))
    var roomResult: AppResult<AssistantCallRoom> =
        AppResult.Success(AssistantCallRoom(CALL_ID, 5, ended = false, room = "ast_1", url = "wss://lk.test", token = "t"))
    val kept = mutableListOf<ScreenedCall>()
    override val history = MutableStateFlow<List<ScreenedCall>>(emptyList())

    override suspend fun line(): AppResult<AssistantLine?> = AppResult.Success(line)

    override suspend fun allocate(): AppResult<AssistantLine> =
        allocateResult.also {
            (it as? AppResult.Success)?.let { ok ->
                line =
                    ok.value
            }
        }

    override suspend fun release(): AppResult<Unit> {
        line = null
        return AppResult.Success(Unit)
    }

    override suspend fun usage(): AppResult<AssistantUsage> = usageResult

    override suspend fun room(callId: String): AppResult<AssistantCallRoom> = roomResult

    override suspend fun keep(call: ScreenedCall) {
        kept += call
        history.value = listOf(call) + history.value.filter { it.id != call.id }
    }

    override suspend fun delete(id: String) {
        history.value = history.value.filter { it.id != id }
    }
}

/** A room whose agent messages the test pushes with [agent]; what the app sends lands in [sent]. */
class FakeRoom(
    private val joins: Boolean = true,
) : AssistantRoomClient,
    AssistantRoomSession {
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var closed = false

    fun agent(json: String) {
        incoming.trySend(json.encodeToByteArray())
    }

    fun hangUp() {
        incoming.close()
    }

    override suspend fun connect(
        url: String,
        token: String,
    ): AppResult<AssistantRoomSession> = if (joins) AppResult.Success(this) else AppResult.Failure(AppError.Network)

    override val messages: Flow<ByteArray> = incoming.receiveAsFlow()

    override suspend fun send(payload: ByteArray): AppResult<Unit> {
        sent += payload.decodeToString()
        return AppResult.Success(Unit)
    }

    override fun close() {
        closed = true
        incoming.close()
    }
}
