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
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Push endpoints, notifications, app calls, Flash messages and video caller ID (docs/architecture.md §8, ADR 0007).
 * A push carries only a random id; [notification] says what it stands for.
 */
@Suppress("TooManyFunctions") // One function per endpoint is how Retrofit describes an API.
interface VoipApi {
    /** Stores this device's push endpoint (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @PUT("v1/push")
    suspend fun registerPush(
        @Body body: PushRegistrationDto,
    ): Response<Unit>

    /** Removes this device's push endpoint (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/push")
    suspend fun unregisterPush(): Response<Unit>

    /** The item behind push [id]. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/notifications/{id}")
    suspend fun notification(
        @Path("id") id: String,
    ): Response<NotificationDto>

    /** Unexpired items, for pushes that never arrived. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/notifications")
    suspend fun pendingNotifications(): Response<List<NotificationDto>>

    /** Starts an app call. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/voip/calls")
    suspend fun startCall(
        @Body body: StartCallDto,
    ): Response<CallSessionDto>

    /** An app call as this user sees it. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/voip/calls/{id}")
    suspend fun call(
        @Path("id") inviteId: String,
    ): Response<CallInviteDto>

    /** `accept`, `decline`, `cancel` or `end` an app call. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/voip/calls/{id}/{action}")
    suspend fun act(
        @Path("id") inviteId: String,
        @Path("action") action: String,
    ): Response<CallInviteDto>

    /** A LiveKit token for a call's room. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/voip/token")
    suspend fun roomToken(
        @Body body: RoomTokenRequestDto,
    ): Response<RoomTokenDto>

    /** Whether app calls and Flash messages reach this user. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/voip/settings")
    suspend fun settings(): Response<VoipSettingsDto>

    /** Turns reachability on or off. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @PUT("v1/voip/settings")
    suspend fun updateSettings(
        @Body body: VoipSettingsDto,
    ): Response<VoipSettingsDto>

    /** Sends a Flash message. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/flash")
    suspend fun sendFlash(
        @Body body: SendFlashDto,
    ): Response<FlashSentDto>

    /** The user's video caller ID. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @GET("v1/me/video")
    suspend fun myVideo(): Response<MyVideoDto>

    /** A presigned upload for a new clip. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/me/video/upload")
    suspend fun videoUpload(): Response<VideoUploadDto>

    /** Uploads a clip to the presigned [url] (no access token; the content type is signed). */
    @PUT
    suspend fun uploadVideo(
        @Url url: String,
        @Header("Content-Type") contentType: String,
        @Body body: RequestBody,
    ): Response<Unit>

    /** Queues the uploaded clip for transcoding. */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/me/video")
    suspend fun submitVideo(
        @Body body: SubmitVideoDto,
    ): Response<MyVideoDto>

    /** Deletes the user's clip (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @DELETE("v1/me/video")
    suspend fun deleteVideo(): Response<Unit>

    /** Download links for clips (no sign-in needed). */
    @POST("v1/videos/links")
    suspend fun videoLinks(
        @Body body: VideoLinksRequestDto,
    ): Response<VideoLinksDto>

    /** Downloads a clip from a presigned [url] (no access token). */
    @Streaming
    @GET
    suspend fun downloadVideo(
        @Url url: String,
    ): Response<ResponseBody>

    /** Reports a clip (204). */
    @Headers("$AUTH_MODE_HEADER: $AUTH_REQUIRED")
    @POST("v1/videos/{id}/report")
    suspend fun reportVideo(
        @Path("id") videoId: String,
    ): Response<Unit>
}
