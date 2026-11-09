package io.github.jeshiomurmu.opencaller.core.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * Family protection (docs/prd.md F-PR-14, ADR 0008) and the scam feed (F-PR-15). A member's phone sends only an
 * alert's kind and two daily counts, and only what the member chose to share; nothing takes a phone number.
 */
@Suppress("TooManyFunctions") // One function per endpoint is how Retrofit describes an API.
interface FamilyApi {
    /** Creates a group with this user as its admin (201). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/family")
    suspend fun create(
        @Body body: CreateFamilyDto,
    ): Response<FamilyDto>

    /** This user's group (404 `not_in_family` when there is none). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/family")
    suspend fun family(): Response<FamilyDto>

    /** A new single-use invite code (admin only, 201). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/family/invites")
    suspend fun invite(): Response<FamilyInviteDto>

    /** What joining with [code] means; changes nothing. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/family/invites/{code}")
    suspend fun preview(
        @Path("code") code: String,
    ): Response<FamilyInvitePreviewDto>

    /** Joins a group with this member's own consent. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/family/join")
    suspend fun join(
        @Body body: JoinFamilyDto,
    ): Response<FamilyDto>

    /** Changes this member's label and what they share; switching something off deletes what the server held of it. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @PUT("v1/family/me")
    suspend fun updateMe(
        @Body body: FamilySharingDto,
    ): Response<FamilyDto>

    /** Leaves the group (204); the admin leaving ends it. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/family/me")
    suspend fun leave(): Response<Unit>

    /** The admin removes member [memberId] (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/family/members/{id}")
    suspend fun remove(
        @Path("id") memberId: String,
    ): Response<Unit>

    /** Tells the admin that the OTP guard warned this member (kind only, 201). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/family/alerts")
    suspend fun alert(
        @Body body: FamilyAlertDto,
    ): Response<FamilyAlertSentDto>

    /** This member's two counts for [day] (`yyyy-mm-dd`, 204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @PUT("v1/family/counts/{day}")
    suspend fun counts(
        @Path("day") day: String,
        @Body body: FamilyCountsDto,
    ): Response<Unit>

    /** The published scam cards of country [iso] (public; the request names only the country). */
    @GET("v1/scam-feed/{iso}")
    suspend fun scamFeed(
        @Path("iso") iso: String,
    ): Response<ScamFeedDto>
}
