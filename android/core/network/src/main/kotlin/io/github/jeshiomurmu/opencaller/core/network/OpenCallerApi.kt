package io.github.jeshiomurmu.opencaller.core.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

/** Marker header read (and removed) by [AuthInterceptor]: which requests carry the access token. */
const val AUTH_MODE_HEADER = "OC-Auth"

/** [AUTH_MODE_HEADER] value: the endpoint needs the access token. */
const val AUTH_REQUIRED = "required"

/** [AUTH_MODE_HEADER] value: the token is sent when the user is signed in (more results), but not needed. */
const val AUTH_OPTIONAL = "optional"

/** The OpenCaller server API v1 (docs/architecture.md §8), except the live lookup ([LookupApi]). */
interface OpenCallerApi {
    /** Sends a sign-in code. */
    @POST("v1/auth/start")
    suspend fun startSignIn(
        @Body body: StartRequest,
    ): Response<StartResponse>

    /** Exchanges a code for tokens. */
    @POST("v1/auth/verify")
    suspend fun verify(
        @Body body: VerifyRequest,
    ): Response<TokenResponse>

    /** Reports numbers as spam or not spam. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/reports")
    suspend fun report(
        @Body body: ReportBatchDto,
    ): Response<ReportBatchResponse>

    /** Explicit search for a number the user typed (50 a day). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/numbers/{e164}")
    suspend fun search(
        @Path("e164") e164: String,
    ): Response<SearchResponse>

    /** Disputes a number's spam label. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/disputes")
    suspend fun dispute(
        @Body body: DisputeRequest,
    ): Response<DisputeResponse>

    /** The latest spam list of [countryIso]. */
    @GET("v1/lists/{countryIso}/manifest")
    suspend fun listManifest(
        @Path("countryIso") countryIso: String,
    ): Response<ListManifestDto>

    /** Downloads a list file (a path from the manifest). */
    @Streaming
    @GET
    suspend fun download(
        @Url path: String,
    ): Response<ResponseBody>
}

/** The k-anonymous live lookup, on its own client with a short timeout (docs/architecture.md §4.6). */
interface LookupApi {
    /** All entries of bucket [bucketId] (first [bits] bits of SHA-256) in country calling code [countryCode]. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_OPTIONAL")
    @GET("v1/lookup/buckets/{cc}/{bucketId}")
    suspend fun bucket(
        @Path("cc") countryCode: Int,
        @Path("bucketId") bucketId: Int,
        @Query("bits") bits: Int,
    ): Response<BucketResponse>
}

/** Token refresh, on a client without the authenticator (a refresh must never trigger another refresh). */
interface AuthApi {
    /** Rotates the refresh token. */
    @POST("v1/auth/refresh")
    fun refresh(
        @Body body: RefreshRequest,
    ): retrofit2.Call<TokenResponse>
}
