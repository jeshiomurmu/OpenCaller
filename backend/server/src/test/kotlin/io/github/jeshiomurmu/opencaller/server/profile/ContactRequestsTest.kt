package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.db.ContactRequests
import io.github.jeshiomurmu.opencaller.server.search.SearchResponse
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days

/** Contact requests (docs/prd.md F-SO-05). */
class ContactRequestsTest {
    private val owner = "+8801712345678"
    private val requester = "+12015550123"

    private suspend fun HttpClient.ask(
        token: String,
        e164: String,
    ): HttpResponse = postJson("/v1/contact-requests", """{"e164":"$e164"}""", bearer(token))

    private suspend fun HttpResponse.assertSent() {
        assertEquals(HttpStatusCode.Accepted, status, bodyAsText())
        assertEquals("""{"status":"sent"}""", bodyAsText())
    }

    private suspend fun HttpClient.incoming(token: String): IncomingContactRequests {
        val response = getWith("/v1/contact-requests", token)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(IncomingContactRequests.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.searchName(
        token: String,
        e164: String,
    ): String? = testJson.decodeFromString(SearchResponse.serializer(), getWith("/v1/numbers/$e164", token).bodyAsText()).info.name

    private suspend fun HttpClient.profile(
        token: String,
        name: String,
        visibility: String,
    ) = assertEquals(
        HttpStatusCode.OK,
        putJson("/v1/me", """{"displayName":"$name","visibility":"$visibility","incognito":false}""", token).status,
    )

    private suspend fun requests() = TestInfra.database.query { ContactRequests.selectAll().count() }

    @Test
    fun anApprovedRequesterSeesTheName() =
        apiTest { server ->
            val ownerToken = signIn(server, owner).accessToken
            val requesterToken = signIn(server, requester).accessToken
            client.profile(ownerToken, "Asha", "nobody")
            client.profile(requesterToken, "Rafi", "nobody")
            assertNull(client.searchName(requesterToken, owner))
            client.ask(requesterToken, owner).assertSent()
            client.ask(requesterToken, owner).assertSent()
            val pending = client.incoming(ownerToken).items.single()
            // Asking shares the requester's number and name with the owner, whatever their visibility.
            assertEquals(requester, pending.requesterE164)
            assertEquals("Rafi", pending.requesterName)
            val decided = client.postJson("/v1/contact-requests/${pending.id}/approve", "{}", bearer(ownerToken))
            assertEquals("""{"id":${pending.id},"status":"approved"}""", decided.bodyAsText())
            assertEquals("Asha", client.searchName(requesterToken, owner))
            val profile = client.getWith("/v1/profiles/$owner", requesterToken).bodyAsText()
            assertEquals("Asha", testJson.decodeFromString(PublicProfileResponse.serializer(), profile).name)
            assertEquals(emptyList(), client.incoming(ownerToken).items)
        }

    @Test
    fun aDeniedRequesterLearnsNothingAndMayAskAgainAfterThirtyDays() =
        apiTest { server ->
            val ownerToken = signIn(server, owner).accessToken
            val requesterToken = signIn(server, requester).accessToken
            client.profile(ownerToken, "Asha", "nobody")
            client.ask(requesterToken, owner).assertSent()
            val id =
                client
                    .incoming(ownerToken)
                    .items
                    .single()
                    .id
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/contact-requests/$id/deny", "{}", bearer(ownerToken)).status)
            assertNull(client.searchName(requesterToken, owner))
            client.ask(requesterToken, owner).assertSent()
            assertEquals(emptyList(), client.incoming(ownerToken).items)
            server.clock.advance(31.days)
            client.ask(signIn(server, requester).accessToken, owner).assertSent()
            assertEquals(1, client.incoming(signIn(server, owner).accessToken).items.size)
        }

    // The answer is the same for every number, so requests can't probe who is registered or hidden.
    @Test
    fun requestsRevealNothingAboutTheNumber() =
        apiTest { server ->
            val ownerToken = signIn(server, owner).accessToken
            val requesterToken = signIn(server, requester).accessToken
            client.profile(ownerToken, "Asha", "everyone")
            client.ask(requesterToken, owner).assertSent()
            client.ask(requesterToken, "+447911123456").assertSent()
            assertEquals(0, requests(), "visible or unregistered numbers store nothing")
            client.ask(requesterToken, requester).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.ask(requesterToken, "12345").assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
        }

    @Test
    fun onlyTheOwnerDecidesAPendingRequest() =
        apiTest { server ->
            val ownerToken = signIn(server, owner).accessToken
            val requesterToken = signIn(server, requester).accessToken
            client.profile(ownerToken, "Asha", "nobody")
            client.ask(requesterToken, owner).assertSent()
            val id =
                client
                    .incoming(ownerToken)
                    .items
                    .single()
                    .id
            client
                .postJson(
                    "/v1/contact-requests/$id/approve",
                    "{}",
                    bearer(requesterToken),
                ).assertProblem(HttpStatusCode.NotFound, "not_found")
            client.postJson("/v1/contact-requests/999/deny", "{}", bearer(ownerToken)).assertProblem(HttpStatusCode.NotFound, "not_found")
            client.postJson("/v1/contact-requests/abc/deny", "{}", bearer(ownerToken)).assertProblem(HttpStatusCode.NotFound, "not_found")
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/contact-requests/$id/deny", "{}", bearer(ownerToken)).status)
            client
                .postJson(
                    "/v1/contact-requests/$id/approve",
                    "{}",
                    bearer(ownerToken),
                ).assertProblem(HttpStatusCode.NotFound, "not_found")
            client.getWith("/v1/contact-requests").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    @Test
    fun twentyRequestsADay() =
        apiTest { server ->
            val token = signIn(server, requester).accessToken
            repeat(20) { client.ask(token, "+4479111234${it.toString().padStart(2, '0')}").assertSent() }
            client.ask(token, "+447911123499").assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
        }
}
