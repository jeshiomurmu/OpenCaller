package io.github.jeshiomurmu.opencaller.server.assistant

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

/** The call assistant's services. */
class AssistantRouteServices(
    val lines: AssistantLineService,
    val calls: AssistantCallService,
    /** `RECORDING_AGENT_TOKEN`, shared by the agents; without it the `/agent/…` routes don't exist. */
    val agentToken: String?,
)

/** `POST`/`GET /v1/lines/assistant`: the user's own assistant number (forward unanswered calls to it). */
@Serializable
data class AssistantLineView(
    val number: String,
    val country: String,
)

/**
 * `GET /v1/assistant/usage`: [usedSeconds] of the calendar month [month] (`yyyy-MM`, UTC); [limitMinutes] a month
 * (0 = no cap) and at most [maxCallMinutes] a call.
 */
@Serializable
data class AssistantUsageView(
    val month: String,
    val usedSeconds: Int,
    val limitMinutes: Int,
    val maxCallMinutes: Int,
)

/**
 * A call the assistant answered: join [room] at [url] with [token] (data messages only) to follow it. Once it has
 * [ended] there is no token. The caller's number is not here: the agent sends it to the app in the room.
 */
@Serializable
data class AssistantCallView(
    val id: String,
    val startedAt: String,
    val ended: Boolean,
    val room: String,
    val url: String? = null,
    val token: String? = null,
    val expiresAt: String? = null,
)

/** The agent answered a call to [number] (the dialed assistant number) in LiveKit room [room]. */
@Serializable
data class AssistantClaimRequest(
    val number: String,
    val room: String,
)

/**
 * The call's id, the LiveKit identity of the user's app ([appIdentity]: send data messages to it only) and how long
 * the agent may stay: [remainingSeconds] (this call, within the month's cap).
 */
@Serializable
data class AssistantClaimResponse(
    val callId: String,
    val appIdentity: String,
    val remainingSeconds: Int,
)

/** The agent's call ended after [durationSeconds]. */
@Serializable
data class AssistantEndRequest(
    val durationSeconds: Int,
)

/** 403 `assistant_unavailable`: no numbers on this server, or none free in the user's country. */
internal fun assistantUnavailable(detail: String) =
    ApiProblem(HttpStatusCode.Forbidden, "assistant_unavailable", "The call assistant isn't available", detail)

/** 404 `assistant_line_not_found`: the user has no assistant number. */
internal fun noAssistantLine() = ApiProblem(HttpStatusCode.NotFound, "assistant_line_not_found", "You have no assistant number")

/** 404 `assistant_call_not_found` (also for another user's call). */
internal fun assistantCallNotFound() = ApiProblem(HttpStatusCode.NotFound, "assistant_call_not_found", "No such assistant call")

/** 404 `number_not_found`: the dialed number is no user's assistant number. */
internal fun assistantNumberNotFound() = ApiProblem(HttpStatusCode.NotFound, "number_not_found", "No user has this assistant number")

/** 429 `assistant_limit`: the user's minutes for this month are used up. */
internal fun assistantLimit() = ApiProblem(HttpStatusCode.TooManyRequests, "assistant_limit", "This month's assistant minutes are used up")
