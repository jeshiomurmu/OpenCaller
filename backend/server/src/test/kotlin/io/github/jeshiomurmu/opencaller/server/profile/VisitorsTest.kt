package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.setSupporterTier
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/** Profile views and searches: recording rules, the supporter lists and their retention (F-SO-02/03/04, TC-SOC-002). */
class VisitorsTest {
    private val store = InMemoryObjectStore()
    private val server = TestServer(objectStore = store)
    private val owner = "+8801712345678"
    private val visitor = "+12015550123"
    private val stranger = "+447911123456"

    private suspend fun HttpResponse.visitors(): VisitorsResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return testJson.decodeFromString(VisitorsResponse.serializer(), bodyAsText())
    }

    private suspend fun HttpResponse.profile(): PublicProfileResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return testJson.decodeFromString(PublicProfileResponse.serializer(), bodyAsText())
    }

    private suspend fun HttpClient.setProfile(
        token: String,
        name: String,
        visibility: String,
        incognito: Boolean = false,
    ) {
        val response = putJson("/v1/me", """{"displayName":"$name","visibility":"$visibility","incognito":$incognito}""", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun views() = TestInfra.database.query { ProfileViews.selectAll().count() }

    // TC-SOC-002: an incognito searcher is not in the target's searcher list; a normal one is, with their name.
    @Test
    fun incognitoSearchesAreLeftOutOfTheSearcherList() =
        apiTest(server) {
            val ownerToken = signIn(server, owner).accessToken
            val visitorToken = signIn(server, visitor).accessToken
            val strangerToken = signIn(server, stranger).accessToken
            setSupporterTier(owner, "supporter")
            client.setProfile(visitorToken, "Incognito Ivan", "everyone", incognito = true)
            client.setProfile(strangerToken, "Visible Vera", "registered_only")
            setSupporterTier(stranger, "gold")
            client.getWith("/v1/numbers/$owner", visitorToken)
            client.getWith("/v1/numbers/$owner", strangerToken)
            val searchers = client.getWith("/v1/me/searchers", ownerToken).visitors()
            assertEquals("search", searchers.kind)
            assertEquals(90, searchers.days)
            assertEquals(1, searchers.total)
            assertEquals("Visible Vera", searchers.items.single().name)
            assertEquals("gold", searchers.items.single().badge)
            assertEquals(server.clock.now.toString(), searchers.items.single().at)
            assertEquals(0, client.getWith("/v1/me/viewers", ownerToken).visitors().total)
        }

    @Test
    fun theListsAreForSupporters() =
        apiTest(server) {
            val token = signIn(server, owner).accessToken
            client.getWith("/v1/me/viewers", token).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
            client.getWith("/v1/me/searchers", token).assertProblem(HttpStatusCode.Forbidden, "supporter_required")
            client.getWith("/v1/me/viewers").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            setSupporterTier(owner, "supporter")
            assertEquals(0, client.getWith("/v1/me/viewers", token).visitors().total)
        }

    // Access tokens live 15 minutes on the test clock, so each step after a clock jump signs in again.
    @Test
    fun viewsAreRecordedOncePerDayAndNeverWithTheNumber() =
        apiTest(server) {
            var ownerToken = signIn(server, owner).accessToken
            var visitorToken = signIn(server, visitor).accessToken
            setSupporterTier(owner, "supporter")
            client.setProfile(ownerToken, "Asha", "nobody")
            client.setProfile(visitorToken, "Hidden Hasan", "nobody")
            client.getWith("/v1/profiles/$owner", visitorToken).profile()
            client.getWith("/v1/profiles/$owner", visitorToken).profile()
            assertEquals(1, views())
            server.clock.advance(25.hours)
            visitorToken = signIn(server, visitor).accessToken
            ownerToken = signIn(server, owner).accessToken
            client.getWith("/v1/profiles/$owner", visitorToken).profile()
            val viewers = client.getWith("/v1/me/viewers", ownerToken).visitors()
            assertEquals(2, viewers.total)
            // A `nobody` visitor is counted but not named, and the number is never part of the answer.
            assertEquals(listOf(null, null), viewers.items.map { it.name })
            assertEquals(false, viewers.toString().contains(visitor))
            // Own profile and incognito views aren't recorded.
            client.getWith("/v1/profiles/$owner", ownerToken).profile()
            client.setProfile(visitorToken, "Hidden Hasan", "nobody", incognito = true)
            server.clock.advance(25.hours)
            visitorToken = signIn(server, visitor).accessToken
            client.getWith("/v1/profiles/$owner", visitorToken).profile()
            assertEquals(2, views())
            ownerToken = signIn(server, owner).accessToken
            val me = testJson.decodeFromString(MeResponse.serializer(), client.getWith("/v1/me", ownerToken).bodyAsText())
            assertEquals(2, me.viewerCount)
        }

    @Test
    fun visitsExpireAfterNinetyDays() =
        apiTest(server) {
            signIn(server, owner)
            val visitorToken = signIn(server, visitor).accessToken
            setSupporterTier(owner, "supporter")
            client.getWith("/v1/profiles/$owner", visitorToken).profile()
            server.clock.advance(89.days)
            assertEquals(1, client.getWith("/v1/me/viewers", signIn(server, owner).accessToken).visitors().total)
            assertEquals(
                0,
                server.deps.retentionJob
                    .run()
                    .visitsExpired,
            )
            server.clock.advance(2.days)
            assertEquals(0, client.getWith("/v1/me/viewers", signIn(server, owner).accessToken).visitors().total)
            assertEquals(
                1,
                server.deps.retentionJob
                    .run()
                    .visitsExpired,
            )
            assertEquals(0, views())
        }

    @Test
    fun aProfileShowsWhatTheViewerMaySee() =
        apiTest(server) {
            val ownerToken = signIn(server, owner).accessToken
            val visitorToken = signIn(server, visitor).accessToken
            client.setProfile(ownerToken, "Asha", "registered_only")
            setSupporterTier(owner, "gold")
            val shown = client.getWith("/v1/profiles/$owner", visitorToken).profile()
            assertEquals("Asha", shown.name)
            assertEquals("gold", shown.badge)
            assertNull(shown.avatarUrl)
            // Hidden names hide the badge too; an unregistered number answers the same way.
            client.setProfile(ownerToken, "Asha", "nobody")
            val hidden = client.getWith("/v1/profiles/$owner", visitorToken).profile()
            assertEquals(PublicProfileResponse(owner, null, null, null), hidden)
            assertEquals(
                PublicProfileResponse(stranger, null, null, null),
                client.getWith("/v1/profiles/$stranger", visitorToken).profile(),
            )
            client.getWith("/v1/profiles/12345", visitorToken).assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            client.getWith("/v1/profiles/$owner").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            assertNotNull(shown.e164)
        }
}
