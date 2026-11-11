package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * The recording line (docs/prd.md F-UT-03, ADR 0009). No request carries a phone number: the server gives the line
 * and a one-time code, and the recording comes back by its id.
 */
interface RecordingApi {
    /** The user's country's line and a fresh code (201); 403 `recording_unavailable` or `supporter_required`. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/lines/recording")
    suspend fun start(): Response<RecordingLineDto>

    /** Session [id]'s state. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/lines/recording/{id}")
    suspend fun session(
        @Path("id") id: String,
    ): Response<RecordingSessionDto>

    /** The user's recordings still on the server. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/recordings")
    suspend fun recordings(): Response<List<RecordingDto>>

    /** A short-lived link to recording [id] and its transcript, if done. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/recordings/{id}/download")
    suspend fun download(
        @Path("id") id: String,
    ): Response<RecordingDownloadDto>

    /** Downloads a recording from a presigned [url] (no access token). */
    @Streaming
    @GET
    suspend fun file(
        @Url url: String,
    ): Response<ResponseBody>

    /** The phone saved recording [id]; the server deletes it soon after (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/recordings/{id}/downloaded")
    suspend fun downloaded(
        @Path("id") id: String,
    ): Response<Unit>

    /** Deletes recording [id] from the server now (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/recordings/{id}")
    suspend fun delete(
        @Path("id") id: String,
    ): Response<Unit>

    /** The transcript switch. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/recordings/settings")
    suspend fun settings(): Response<RecordingSettingsDto>

    /** Turns transcripts on or off. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @PUT("v1/recordings/settings")
    suspend fun updateSettings(
        @Body body: RecordingSettingsDto,
    ): Response<RecordingSettingsDto>
}

/** `POST /v1/lines/recording`. */
@Serializable
data class RecordingLineDto(
    val sessionId: String,
    val number: String,
    val code: String,
    val expiresAt: String,
)

/** `GET /v1/lines/recording/{id}`: `pending`, `active`, `ended` or `failed`. */
@Serializable
data class RecordingSessionDto(
    val sessionId: String,
    val state: String,
    val recordingId: String? = null,
)

/** A recording on the server; [transcript] is `off`, `pending`, `working`, `done` or `failed`. */
@Serializable
data class RecordingDto(
    val id: String,
    val createdAt: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val transcript: String,
    val downloaded: Boolean,
    val deleteBy: String,
)

/** `GET /v1/recordings/{id}/download`. */
@Serializable
data class RecordingDownloadDto(
    val url: String,
    val contentType: String,
    val sizeBytes: Long,
    val expiresInSeconds: Int,
    val transcript: String? = null,
)

/** `GET`/`PUT /v1/recordings/settings`. */
@Serializable
data class RecordingSettingsDto(
    val transcripts: Boolean,
)
