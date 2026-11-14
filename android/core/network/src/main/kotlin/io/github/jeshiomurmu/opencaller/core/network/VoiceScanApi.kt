package io.github.jeshiomurmu.opencaller.core.network

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * The voice scan on the recording line (docs/prd.md F-PR-13, ADR 0011). No request carries a phone number: the server
 * gives the line and a one-time code, and only the outcome comes back (a probability and scam-cue ids).
 */
interface VoiceScanApi {
    /** The user's country's line and a fresh code (201); 403 `scanner_unavailable` or `supporter_required`. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/lines/scanner")
    suspend fun start(): Response<RecordingLineDto>

    /** Scan [id]'s state and, once ended, its result. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/lines/scanner/{id}")
    suspend fun scan(
        @Path("id") id: String,
    ): Response<ScanSessionDto>
}

/** `GET /v1/lines/scanner/{id}`: the state, and once `ended` the spoof probability and the scam-cue ids. */
@Serializable
data class ScanSessionDto(
    val sessionId: String,
    val state: String,
    val spoofProbability: Float? = null,
    val cues: List<String> = emptyList(),
)
