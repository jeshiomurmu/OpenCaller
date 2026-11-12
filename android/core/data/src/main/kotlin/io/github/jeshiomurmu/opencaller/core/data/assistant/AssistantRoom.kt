package io.github.jeshiomurmu.opencaller.core.data.assistant

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import kotlinx.coroutines.flow.Flow

/**
 * A connection to an assistant call's LiveKit room for data messages only (ADR 0010). Builds with LiveKit
 * (`feature:voip`) provide one; F-Droid builds don't, and the assistant's live screen says so.
 */
interface AssistantRoomClient {
    /** Joins the room at [url] with [token]; the session ends when [AssistantRoomSession.close] is called. */
    suspend fun connect(
        url: String,
        token: String,
    ): AppResult<AssistantRoomSession>
}

/** A joined room: the agent's messages on topic [AssistantMessages.TOPIC], and a way to send ours. */
interface AssistantRoomSession {
    /** Payloads the agent sent on the assistant topic; completes when the room closes. */
    val messages: Flow<ByteArray>

    /** Sends [payload] to the agent on the assistant topic (reliable). */
    suspend fun send(payload: ByteArray): AppResult<Unit>

    /** Leaves the room (the call goes on without the app). */
    fun close()
}

/** Shows that the assistant answered a call (a notification that opens the live screen). */
fun interface AssistantCallNotices {
    /** The assistant answered call [callId] at [startedAtMillis]. */
    fun show(
        callId: String,
        startedAtMillis: Long,
    )
}
