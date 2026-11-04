package io.github.jeshiomurmu.opencaller.core.data

import android.os.Build
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallHandler
import io.github.jeshiomurmu.opencaller.core.data.voip.ClipNetwork
import io.github.jeshiomurmu.opencaller.core.data.voip.FlashRepository
import io.github.jeshiomurmu.opencaller.core.data.voip.NotificationRouter
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import io.github.jeshiomurmu.opencaller.core.data.voip.VideoClips
import io.github.jeshiomurmu.opencaller.core.data.voip.VoipPreferences
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.AppCall
import io.github.jeshiomurmu.opencaller.core.model.AppCallState
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.testing.FakeClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Optional
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/** Push items routed to calls and Flash messages, the push endpoint on the server, and the clip cache (ADR 0007). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.VANILLA_ICE_CREAM])
class VoipDataTest {
    private val server = MockWebServer().apply { start() }
    private val db = inMemoryDatabase()
    private val clock = FakeClock()
    private val tokens = MemoryTokens(AuthTokens("access", Long.MAX_VALUE, "refresh", Long.MAX_VALUE))
    private val api = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, clock).voipApi
    private val billing = OpenCallerNetwork(ServerConfig(server.url("/").toString()), tokens, clock).billingApi
    private val preferences = VoipPreferences(tempPreferences("voip"))
    private val flash = FlashRepository(api, clock, DatabaseAccess { db }, RealDispatchers)
    private val shown = mutableListOf<FlashMessage>()
    private val invites = mutableListOf<AppCall>()
    private val updates = mutableListOf<AppCall>()
    private val handler =
        object : AppCallHandler {
            override fun onInvite(call: AppCall) {
                invites += call
            }

            override fun onUpdate(call: AppCall) {
                updates += call
            }
        }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun router(calls: Optional<AppCallHandler> = Optional.of(handler)) = NotificationRouter(api, calls, flash, { shown += it }, clock)

    private val itemId = "7b0a0c7e-1f7c-4d73-9d0e-2cbe0e6f6a10"
    private val videoId = "3f2d1c4b-5a69-4e7f-8a9b-0c1d2e3f4a5b"

    private fun invite(
        kind: String = "call_invite",
        state: String = "ringing",
        role: String = "callee",
    ) = jsonResponse(
        200,
        """{"id":"$itemId","kind":"$kind","createdAt":"2026-11-04T10:00:00Z","call":{"inviteId":"i1","state":"$state",""" +
            """"role":"$role","number":"+12015550123","name":"Asha","videoId":"$videoId","ringUntil":"2026-11-04T10:00:45Z"}}""",
    )

    private fun flashItem(id: String = "f1") =
        jsonResponse(
            200,
            """{"id":"$itemId","kind":"flash","createdAt":"x","flash":{"id":"$id","kind":"running_late","from":"+12015550123","sentAt":"2026-11-04T10:00:00Z"}}""",
        )

    @Test
    fun aCallInviteRingsAndAnUpdateFollows() =
        runBlocking {
            server.enqueue(invite())
            server.enqueue(invite(kind = "call_update", state = "cancelled"))
            assertTrue(router().onPush(itemId))
            assertTrue(router().onPush(itemId))
            assertEquals("/v1/notifications/$itemId", server.takeRequest().url.encodedPath)
            val call = invites.single()
            assertEquals(AppCallState.RINGING, call.state)
            assertEquals("+12015550123", call.number?.e164)
            assertEquals(videoId, call.videoId)
            assertEquals(1_793_786_445_000L, call.ringUntilMillis)
            assertEquals(AppCallState.CANCELLED, updates.single().state)
        }

    @Test
    fun withoutAppCallsInTheBuildCallPushesAreIgnored() =
        runBlocking {
            server.enqueue(invite())
            assertTrue(router(Optional.empty()).onPush(itemId))
            assertTrue(invites.isEmpty())
        }

    @Test
    fun aFlashMessageIsKeptAndShownOnce() =
        runBlocking {
            server.enqueue(flashItem())
            server.enqueue(flashItem())
            router().onPush(itemId)
            router().onPush(itemId)
            assertEquals(FlashKind.RUNNING_LATE, shown.single().kind)
            val kept = (flash.recent().first() as io.github.jeshiomurmu.opencaller.core.common.AppResult.Success).value
            assertEquals(listOf("f1"), kept.map { it.id })
            assertFalse(kept.single().outgoing)
        }

    @Test
    fun anInvalidPushIdIsNotFetched() =
        runBlocking {
            assertFalse(router().onPush("../me"))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun theEndpointGoesToTheServerOnlyWhileSignedIn() =
        runBlocking {
            val signedIn = MutableStateFlow(false)
            val account =
                object : AccountStatus {
                    override val signedIn = signedIn
                    override val serverConfigured = true
                    override val unlistPageUrl: String? = null
                }
            val registrar = PushRegistrar(api, billing, account, preferences)
            registrar.onNewEndpoint(PushEndpoint.WebPush("https://up.example/1", "key", "auth"))
            assertEquals(0, server.requestCount)
            signedIn.value = true
            server.enqueue(MockResponse.Builder().code(204).build())
            registrar.resend()
            val put = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/push", put.url.encodedPath)
            assertEquals("""{"type":"webpush","endpoint":"https://up.example/1","p256dh":"key","auth":"auth"}""", put.body?.utf8())
        }

    @Test
    fun clipsDownloadOnWifiOnlyByDefault() =
        runBlocking {
            var unmetered = false
            val dir = createTempDirectory("clips").toFile()
            val clips = VideoClips(dir, api, preferences, ClipNetwork { mobileData -> mobileData || unmetered }, clock, DatabaseAccess { db }, RealDispatchers)
            clips.download(listOf(videoId))
            assertEquals(0, server.requestCount)

            unmetered = true
            server.enqueue(jsonResponse(200, """{"links":[{"id":"$videoId","url":"${server.url("/clip")}"},{"id":"../x","url":"x"}],"expiresAt":"x"}"""))
            server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(ByteArray(1_000) { 7 })).build())
            clips.download(listOf(videoId))
            assertEquals(1_000L, clips.clip(videoId)?.length())
            assertNull(clips.clip("../x"))
            val links = server.takeRequest()
            assertNull(links.headers["Authorization"])
            assertEquals(2, server.requestCount) // the bad id was never fetched

            preferences.setClipsOnMobileData(true)
            unmetered = false
            server.enqueue(jsonResponse(200, """{"links":[],"expiresAt":"x"}"""))
            clips.download(listOf("aaaaaaaa-5a69-4e7f-8a9b-0c1d2e3f4a5b"))
            assertEquals(3, server.requestCount)
        }
}
