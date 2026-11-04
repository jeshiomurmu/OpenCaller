package io.github.jeshiomurmu.opencaller.core.network

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Phase 11 endpoints as the server documents them (openapi.yaml): paths, bodies, tokens and errors. */
class VoipApiTest {
    private val server = MockWebServer()
    private val tokens = MemoryTokenStore()
    private val now = 1_000_000L
    private lateinit var api: VoipApi

    @Before
    fun setUp() {
        server.start()
        runBlocking { tokens.write(AuthTokens("access-1", now + 60_000, "refresh-1", now + 120_000)) }
        api = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, { now }).voipApi
    }

    @After
    fun tearDown() = server.close()

    private fun respond(
        code: Int,
        body: String = "",
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", if (code in 200..299) "application/json" else "application/problem+json")
            .body(body)
            .build(),
    )

    @Test
    fun pushRegistrationSendsOnlyTheFieldsOfItsType() =
        runBlocking {
            respond(204)
            respond(204)
            assertTrue(apiCallNoContent { api.registerPush(PushRegistrationDto(type = "fcm", token = "fcm-token")) } is AppResult.Success)
            apiCallNoContent { api.registerPush(PushRegistrationDto("webpush", endpoint = "https://up.example/1", p256dh = "k", auth = "a")) }
            val fcm = server.takeRequest()
            assertEquals("PUT", fcm.method)
            assertEquals("/v1/push", fcm.url.encodedPath)
            assertEquals("Bearer access-1", fcm.headers["Authorization"])
            assertEquals("""{"type":"fcm","token":"fcm-token"}""", fcm.body?.utf8())
            assertEquals(
                """{"type":"webpush","endpoint":"https://up.example/1","p256dh":"k","auth":"a"}""",
                server.takeRequest().body?.utf8(),
            )
        }

    @Test
    fun aCallInviteNotificationIsRead() =
        runBlocking {
            respond(
                200,
                """{"id":"n1","kind":"call_invite","createdAt":"2026-11-04T10:00:00Z","call":{"inviteId":"i1","state":"ringing",""" +
                    """"role":"callee","number":"+12015550123","name":"Asha","videoId":"v1","ringUntil":"2026-11-04T10:00:45Z"},"later":1}""",
            )
            val note = (apiCall { api.notification("n1") } as AppResult.Success).value
            assertEquals("/v1/notifications/n1", server.takeRequest().url.encodedPath)
            assertEquals("call_invite", note.kind)
            assertEquals("v1", note.call?.videoId)
            assertNull(note.flash)
        }

    @Test
    fun callsTokensAndActionsUseTheDocumentedPaths() =
        runBlocking {
            respond(
                200,
                """{"inviteId":"i1","room":"a_b_n","url":"wss://lk.example","token":"t","tokenExpiresAt":"x","ringUntil":"y","reachedDevices":2}""",
            )
            respond(200, """{"room":"a_b_n","url":"wss://lk.example","token":"t2","expiresAt":"x"}""")
            respond(200, """{"inviteId":"i1","state":"accepted","role":"callee","number":"+12015550123","ringUntil":"y"}""")
            assertEquals(2, (apiCall { api.startCall(StartCallDto("+12015550123")) } as AppResult.Success).value.reachedDevices)
            apiCall { api.roomToken(RoomTokenRequestDto("i1")) }
            apiCall { api.act("i1", "accept") }
            val start = server.takeRequest()
            assertEquals("/v1/voip/calls", start.url.encodedPath)
            assertEquals("""{"to":"+12015550123"}""", start.body?.utf8())
            assertEquals("""{"inviteId":"i1"}""", server.takeRequest().body?.utf8())
            assertEquals("/v1/voip/calls/i1/accept", server.takeRequest().url.encodedPath)
        }

    @Test
    fun anUnreachableNumberIsANotFound() =
        runBlocking {
            respond(404, """{"type":"urn:opencaller:problem:not_reachable","title":"x","status":404,"code":"not_reachable"}""")
            respond(400, """{"type":"urn:opencaller:problem:invalid_flash","title":"x","status":400,"code":"invalid_flash"}""")
            assertEquals(AppError.NotFound, (apiCall { api.startCall(StartCallDto("+12015550123")) } as AppResult.Failure).error)
            assertEquals(AppError.Validation("invalid_flash"), (apiCall { api.sendFlash(SendFlashDto("+1", "x")) } as AppResult.Failure).error)
        }

    @Test
    fun videoLinksAndUploadsCarryNoToken() =
        runBlocking {
            respond(200, """{"links":[{"id":"v1","url":"http://objects/v1"}],"expiresAt":"x"}""")
            respond(200)
            apiCall { api.videoLinks(VideoLinksRequestDto(listOf("v1", "v2"))) }
            apiCallNoContent { api.uploadVideo(server.url("/put").toString(), "video/mp4", byteArrayOf(1, 2).toRequestBody("video/mp4".toMediaType())) }
            val links = server.takeRequest()
            assertNull(links.headers["Authorization"])
            assertEquals("""{"ids":["v1","v2"]}""", links.body?.utf8())
            val upload = server.takeRequest()
            assertNull(upload.headers["Authorization"])
            assertEquals("video/mp4", upload.headers["Content-Type"])
        }

    @Test
    fun anOlderServerHasNoPhase11Features() {
        val info = OpenCallerNetwork.json.decodeFromString(ServerInfoDto.serializer(), """{"selfHosted":false,"billing":{}}""")
        assertEquals(FeaturesDto(), info.features)
        val newer =
            OpenCallerNetwork.json.decodeFromString(
                ServerInfoDto.serializer(),
                """{"features":{"appCalls":true,"flash":true,"fcm":false,"webPushVapidKey":"BK"}}""",
            )
        assertEquals(FeaturesDto(appCalls = true, flash = true, fcm = false, webPushVapidKey = "BK"), newer.features)
    }
}
