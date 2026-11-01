package io.github.jeshiomurmu.opencaller.core.network

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
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

    /** Sender IDs of [countryIso] changed after [since] (epoch ms; `null` for all). */
    @GET("v1/sender-ids/{countryIso}")
    suspend fun senderIds(
        @Path("countryIso") countryIso: String,
        @Query("since") since: Long?,
    ): Response<SenderIdsDto>

    /** SMS Retriever app hashes and flash-call verification ranges changed after [since]. */
    @GET("v1/otp-sources")
    suspend fun otpSources(
        @Query("since") since: Long?,
    ): Response<OtpSourcesDto>

    /** The known-bad link hosts as hash prefixes (the whole list). */
    @GET("v1/link-prefixes")
    suspend fun linkPrefixes(): Response<LinkPrefixesDto>

    /** Downloads a list file (a path from the manifest). */
    @Streaming
    @GET
    suspend fun download(
        @Url path: String,
    ): Response<ResponseBody>
}

/** Profiles, visitors and contact requests (docs/architecture.md §8, Phase 8); all need the access token. */
@Suppress("TooManyFunctions") // One function per endpoint is how Retrofit describes an API.
interface ProfileApi {
    /** The signed-in user's profile. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/me")
    suspend fun me(): Response<MeDto>

    /** Replaces the editable profile. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @PUT("v1/me")
    suspend fun updateMe(
        @Body body: UpdateMeDto,
    ): Response<MeDto>

    /** Deletes the account (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/me")
    suspend fun deleteMe(): Response<Unit>

    /** A presigned upload URL for a new profile picture. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/me/avatar")
    suspend fun avatarUpload(): Response<AvatarUploadDto>

    /** Uploads the picture to the presigned [url] (no access token; the content type is signed). */
    @PUT
    suspend fun upload(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Body body: RequestBody,
    ): Response<Unit>

    /** Downloads a picture from a presigned [url] (no access token). */
    @Streaming
    @GET
    suspend fun picture(
        @Url url: String,
    ): Response<ResponseBody>

    /** Who viewed the profile (supporters). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/me/viewers")
    suspend fun viewers(): Response<VisitorsDto>

    /** Who searched for the number (supporters). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/me/searchers")
    suspend fun searchers(): Response<VisitorsDto>

    /** Opens [e164]'s profile (recorded as a view unless incognito). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/profiles/{e164}")
    suspend fun profile(
        @Path("e164") e164: String,
    ): Response<PublicProfileDto>

    /** Asks the owner of a number for their name (always answers `sent`). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/contact-requests")
    suspend fun requestContact(
        @Body body: ContactRequestDto,
    ): Response<ContactRequestSentDto>

    /** Pending requests to the signed-in user. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/contact-requests")
    suspend fun contactRequests(): Response<IncomingContactRequestsDto>

    /** Approves or denies ([decision] is `approve` or `deny`) request [id]. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/contact-requests/{id}/{decision}")
    suspend fun decide(
        @Path("id") id: Long,
        @Path("decision") decision: String,
    ): Response<ContactRequestDecisionDto>
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
