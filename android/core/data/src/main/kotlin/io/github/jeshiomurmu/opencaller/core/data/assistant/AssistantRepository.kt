package io.github.jeshiomurmu.opencaller.core.data.assistant

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.profile.millisOf
import io.github.jeshiomurmu.opencaller.core.model.AssistantCallRoom
import io.github.jeshiomurmu.opencaller.core.model.AssistantLine
import io.github.jeshiomurmu.opencaller.core.model.AssistantUsage
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreenedCall
import io.github.jeshiomurmu.opencaller.core.network.AssistantApi
import io.github.jeshiomurmu.opencaller.core.network.AssistantCallDto
import io.github.jeshiomurmu.opencaller.core.network.AssistantLineDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import kotlinx.coroutines.flow.Flow

/** The call assistant as the screens use it ([AssistantRepository]; a fake in tests). */
interface CallAssistant {
    /** Screened calls on this phone, newest first. */
    val history: Flow<List<ScreenedCall>>

    /** The user's assistant number, or `null` if they have none yet. */
    suspend fun line(): AppResult<AssistantLine?>

    /** The user's assistant number, getting one if needed. */
    suspend fun allocate(): AppResult<AssistantLine>

    /** Gives the number back (after the user cancelled the forwarding). */
    suspend fun release(): AppResult<Unit>

    /** Minutes used this month. */
    suspend fun usage(): AppResult<AssistantUsage>

    /** Call [callId]'s room. */
    suspend fun room(callId: String): AppResult<AssistantCallRoom>

    /** Saves [call] on this phone (again as it goes on). */
    suspend fun keep(call: ScreenedCall)

    /** Deletes screened call [id] from this phone. */
    suspend fun delete(id: String)
}

/**
 * The call assistant (docs/prd.md F-UT-04, ADR 0010). The number and the minutes come from the server; what the
 * assistant learned about a caller comes from the agent in the call's room and is kept on this phone only, for
 * [KEEP_DAYS] days.
 */
class AssistantRepository(
    private val api: AssistantApi,
    private val store: ScreenedCallStore,
    private val clock: Clock,
) : CallAssistant {
    override val history: Flow<List<ScreenedCall>> = store.recent(HISTORY_SIZE)

    override suspend fun line(): AppResult<AssistantLine?> =
        when (val result = apiCall { api.line() }) {
            is AppResult.Success -> result.value.toModel()?.let { AppResult.Success(it) } ?: invalid()
            is AppResult.Failure -> if (result.error == AppError.NotFound) AppResult.Success(null) else result
        }

    override suspend fun allocate(): AppResult<AssistantLine> =
        apiCall { api.allocate() }.flatMap { dto -> dto.toModel()?.let { AppResult.Success(it) } ?: invalid() }

    override suspend fun release(): AppResult<Unit> = apiCallNoContent { api.release() }

    override suspend fun usage(): AppResult<AssistantUsage> =
        apiCall { api.usage() }.map { AssistantUsage(it.month, it.usedSeconds, it.limitMinutes, it.maxCallMinutes) }

    override suspend fun room(callId: String): AppResult<AssistantCallRoom> =
        if (ID.matches(callId)) apiCall { api.call(callId) }.map { it.toModel() } else AppResult.Failure(AppError.NotFound)

    override suspend fun keep(call: ScreenedCall) {
        store.put(call)
        store.deleteOlderThan(clock.nowMillis() - KEEP_DAYS * DAY_MILLIS)
    }

    override suspend fun delete(id: String) = store.remove(id)

    private fun <T> invalid(): AppResult<T> = AppResult.Failure(AppError.Validation(INVALID_LINE))

    /** Limits. */
    companion object {
        /** How long screened calls stay on the phone. */
        const val KEEP_DAYS = 90L

        /** How many the history shows. */
        const val HISTORY_SIZE = 200

        private const val DAY_MILLIS = 86_400_000L
        private const val INVALID_LINE = "invalid_assistant_line"
        private val ID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}

internal fun AssistantLineDto.toModel(): AssistantLine? =
    if (PhoneNumber.isE164(number) && COUNTRY.matches(country)) AssistantLine(PhoneNumber(number), country) else null

internal fun AssistantCallDto.toModel(): AssistantCallRoom =
    AssistantCallRoom(
        callId = id,
        startedAtMillis = millisOf(startedAt),
        ended = ended,
        room = room,
        url = url,
        token = token,
    )

private val COUNTRY = Regex("^[A-Z]{2}$")
