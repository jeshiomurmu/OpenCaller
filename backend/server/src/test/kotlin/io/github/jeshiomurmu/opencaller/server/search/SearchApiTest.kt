package io.github.jeshiomurmu.opencaller.server.search

import io.github.jeshiomurmu.opencaller.server.db.ProfileViews
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.insertUser
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.github.jeshiomurmu.opencaller.server.testing.updateProfile
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `GET /v1/numbers/{e164}`: results, "who searched me" records, daily quota (TC-API-009). */
class SearchApiTest {
    private val searcher = "+8801712345678"
    private val target = "+8801812345678"

    private suspend fun HttpResponse.result(): SearchResponse {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        return testJson.decodeFromString(SearchResponse.serializer(), bodyAsText())
    }

    private suspend fun searches() = TestInfra.database.query { ProfileViews.selectAll().toList() }

    @Test
    fun aSearchReturnsWhatIsKnownAndIsRecorded() =
        apiTest { server ->
            val targetId = insertUser(target, "Registered Person", Visibility.REGISTERED_ONLY)
            insertReports(target, 2, at = server.clock.now)
            server.deps.scoringJob.run()
            val token = signIn(server, searcher).accessToken
            val response = client.getWith("/v1/numbers/$target", token)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val result = response.result()
            assertEquals(target, result.e164)
            assertEquals("Registered Person", result.info.name)
            assertEquals(2, result.info.reportCount)
            val view = searches().single()
            assertEquals(targetId, view[ProfileViews.viewedUserId])
            assertEquals(SearchService.SEARCH_KIND, view[ProfileViews.kind])
            assertEquals(server.clock.now, view[ProfileViews.at].toInstant())
            // "+" may arrive percent-encoded.
            assertEquals(target, client.getWith("/v1/numbers/%2B8801812345678", token).result().e164)
        }

    @Test
    fun nobodyProfilesAndUnknownNumbersHaveNoName() =
        apiTest { server ->
            insertUser(target, "Hidden Person", Visibility.NOBODY)
            val token = signIn(server, searcher).accessToken
            assertNull(
                client
                    .getWith("/v1/numbers/$target", token)
                    .result()
                    .info.name,
            )
            val unknown = client.getWith("/v1/numbers/+918123456789", token).result()
            assertEquals(0, unknown.info.reportCount)
            assertNull(unknown.info.spamScore)
            // Unknown numbers have no owner, so nothing to record; the `nobody` user was still searched.
            assertEquals(1, searches().size)
        }

    @Test
    fun incognitoSearchesAreNotRecorded() =
        apiTest { server ->
            insertUser(target, "Someone")
            val token = signIn(server, searcher).accessToken
            updateProfile(searcher, incognito = true)
            client.getWith("/v1/numbers/$target", token).result()
            // Searching your own number isn't recorded either.
            updateProfile(searcher, incognito = false)
            client.getWith("/v1/numbers/$searcher", token).result()
            assertTrue(searches().isEmpty())
        }

    // TC-API-009: the 51st search in a day → 429 with Retry-After; invalid numbers don't use the quota.
    @Test
    fun theFiftyFirstSearchOfTheDayIsRefused() =
        apiTest { server ->
            val token = signIn(server, searcher).accessToken
            client.getWith("/v1/numbers/12345", token).assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            repeat(50) { assertEquals(HttpStatusCode.OK, client.getWith("/v1/numbers/$target", token).status) }
            val refused = client.getWith("/v1/numbers/$target", token)
            refused.assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
            val retryAfter = refused.headers[HttpHeaders.RetryAfter]!!.toLong()
            assertTrue(retryAfter in 86_000..86_400, "Retry-After $retryAfter")
            // Each user has their own quota.
            assertEquals(HttpStatusCode.OK, client.getWith("/v1/numbers/$target", signIn(server, "+12015550123").accessToken).status)
        }

    @Test
    fun anAccessTokenIsRequired() =
        apiTest {
            client.getWith("/v1/numbers/$target").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }
}
