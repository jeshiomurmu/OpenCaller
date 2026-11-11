package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The call assistant (docs/prd.md F-UT-04, ADR 0010). No request carries the caller's number: the agent sends it to
 * the phone in the call's LiveKit room, and the server keeps only minutes.
 */
interface AssistantApi {
    /** The user's assistant number (201), the same one on every call; 403 `assistant_unavailable` or `supporter_required`. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/lines/assistant")
    suspend fun allocate(): Response<AssistantLineDto>

    /** The user's assistant number; 404 `assistant_line_not_found` if they have none. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/lines/assistant")
    suspend fun line(): Response<AssistantLineDto>

    /** Gives the number back (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/lines/assistant")
    suspend fun release(): Response<Unit>

    /** Minutes used this month. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/assistant/usage")
    suspend fun usage(): Response<AssistantUsageDto>

    /** Call [id]: its room and a data-only token while it lasts. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/assistant/calls/{id}")
    suspend fun call(
        @Path("id") id: String,
    ): Response<AssistantCallDto>
}

/** `POST`/`GET /v1/lines/assistant`. */
@Serializable
data class AssistantLineDto(
    val number: String,
    val country: String,
)

/** `GET /v1/assistant/usage`: [limitMinutes] 0 means no cap. */
@Serializable
data class AssistantUsageDto(
    val month: String,
    val usedSeconds: Int,
    val limitMinutes: Int,
    val maxCallMinutes: Int,
)

/** A call the assistant answered; [url] and [token] only while it lasts. */
@Serializable
data class AssistantCallDto(
    val id: String,
    val startedAt: String,
    val ended: Boolean,
    val room: String,
    val url: String? = null,
    val token: String? = null,
    val expiresAt: String? = null,
)
