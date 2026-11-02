package io.github.jeshiomurmu.opencaller.server.business

import io.github.jeshiomurmu.opencaller.server.db.BusinessClaimDocuments
import io.github.jeshiomurmu.opencaller.server.db.BusinessClaims
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.deleteWith
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertBusiness
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.HttpClient
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Business claims from the claimant's side (docs/prd.md F-BZ-01, TC-BIZ-001). */
class BusinessClaimsTest {
    private val store = InMemoryObjectStore()
    private val server = TestServer(objectStore = store)
    private val claimant = "+8801712345678"
    private val businessNumber = "+8801812345678"

    @Test
    fun tcBiz001_aClaimWithoutTheCodeToThatNumberCantUploadOrBeSubmitted() =
        apiTest(server) {
            val token = signIn(it, claimant).accessToken
            val claim = client.startClaim(token).claim(HttpStatusCode.Created)
            assertEquals("verifying", claim.status)
            assertEquals(300, claim.codeExpiresInSeconds)
            // The code goes to the claimed number, not to the claimant.
            val code = it.otp.lastCodeFor(businessNumber)

            client.upload(token, claim.id).assertProblem(HttpStatusCode.Forbidden, "number_not_verified")
            client.submit(token, claim.id, "claims/${claim.id}/doc-x.pdf").assertProblem(HttpStatusCode.Forbidden, "number_not_verified")
            val wrong = if (code == "000000") "111111" else "000000"
            client
                .postJson(
                    "/v1/business/claims/${claim.id}/code",
                    """{"code":"$wrong"}""",
                    bearer(token),
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_code")
            assertEquals(
                "verifying",
                client
                    .getWith("/v1/business/claims", token)
                    .claims()
                    .single()
                    .status,
            )

            val verified = client.postJson("/v1/business/claims/${claim.id}/code", """{"code":"$code"}""", bearer(token)).claim()
            assertEquals("verified", verified.status)
            submitWithDocument(token, claim.id)
            assertEquals(
                "submitted",
                client
                    .getWith("/v1/business/claims", token)
                    .claims()
                    .single()
                    .status,
            )
        }

    @Test
    fun aCodeCanComeByPhoneCallAndAnotherUserCantProveSomeoneElsesClaim() =
        apiTest(server) {
            val token = signIn(it, claimant).accessToken
            val claim = client.startClaim(token, method = "voice").claim(HttpStatusCode.Created)
            assertEquals("voice", claim.method)
            val code = it.voice.lastCodeFor(businessNumber)
            assertTrue(it.otp.sent.none { (number, _) -> number.value == businessNumber })

            val other = signIn(it, "+8801912345678").accessToken
            client
                .postJson(
                    "/v1/business/claims/${claim.id}/code",
                    """{"code":"$code"}""",
                    bearer(other),
                ).assertProblem(HttpStatusCode.NotFound, "claim_not_found")
            // A second claim on the same number waits until this one is decided or withdrawn.
            client.startClaim(other).assertProblem(HttpStatusCode.Conflict, "claim_open")
            assertEquals(
                "verified",
                client.postJson("/v1/business/claims/${claim.id}/code", """{"code":"$code"}""", bearer(token)).claim().status,
            )
        }

    @Test
    fun claimsAreValidated() =
        apiTest(server) {
            val token = signIn(it, claimant).accessToken
            client.startClaim(token, category = "casino").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.startClaim(token, number = "+80012345678").assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            client.startClaim(token, number = "01812345678").assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            client.startClaim(token, method = "fax").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client
                .postJson("/v1/business/claims", claimJson(website = "http://example.com"), bearer(token))
                .assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.postJson("/v1/business/claims", claimJson(), emptyMap()).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            insertBusiness(businessNumber, "Example Bank")
            client.startClaim(token).assertProblem(HttpStatusCode.Conflict, "already_verified")
        }

    @Test
    fun uploadsAreCheckedOnSubmit() =
        apiTest(server) {
            val token = signIn(it, claimant).accessToken
            val id = verifiedClaim(token)
            client
                .postJson("/v1/business/claims/$id/uploads", """{"kind":"document","contentType":"text/html"}""", bearer(token))
                .assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            val document = client.upload(token, id).uploadKey()
            // Not uploaded yet.
            client.submit(token, id, document).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            // Uploaded with another type than the URL was signed for.
            store.upload(document, ByteArray(10), "image/png")
            client.submit(token, id, document).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            // Too large.
            store.upload(document, ByteArray(UploadKind.DOCUMENT.maxBytes.toInt() + 1), "application/pdf")
            client.submit(token, id, document).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            // A key that wasn't issued for this claim.
            client.submit(token, id, "claims/${UUID.randomUUID()}/doc-a.pdf").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            store.upload(document, ByteArray(1_000), "application/pdf")
            assertEquals("submitted", client.submit(token, id, document).claim().status)
            client.submit(token, id, document).assertProblem(HttpStatusCode.Conflict, "claim_state")
        }

    @Test
    fun aWithdrawnClaimLosesItsFilesAndFreesTheNumber() =
        apiTest(server) {
            val token = signIn(it, claimant).accessToken
            val id = verifiedClaim(token)
            val document = submitWithDocument(token, id)
            assertEquals(HttpStatusCode.NoContent, client.deleteWith("/v1/business/claims/$id", token).status)
            assertNull(store.objects[document])
            assertEquals(
                "withdrawn",
                client
                    .getWith("/v1/business/claims", token)
                    .claims()
                    .single()
                    .status,
            )
            client.deleteWith("/v1/business/claims/$id", token).assertProblem(HttpStatusCode.Conflict, "claim_state")
            assertEquals(HttpStatusCode.Created, client.startClaim(token).status)
        }

    @Test
    fun filesOfDecidedClaimsAreDeletedAfter90Days() =
        apiTest(server) {
            val token = signIn(it, claimant).accessToken
            val id = verifiedClaim(token)
            val document = submitWithDocument(token, id)
            assertEquals(ReviewOutcome.Rejected, it.deps.claimReview.reject(UUID.fromString(id), "admin", "Blurry document"))
            val rejected = client.getWith("/v1/business/claims", token).claims().single()
            assertEquals("rejected", rejected.status)
            assertEquals("Blurry document", rejected.reviewNote)

            it.deps.retentionJob.run()
            assertTrue(store.objects.containsKey(document), "kept for 90 days")
            TestInfra.database.query {
                BusinessClaims.update({ BusinessClaims.id eq UUID.fromString(id) }) {
                    it[decidedAt] = OffsetDateTime.now(server.clock).minusDays(91).withOffsetSameInstant(ZoneOffset.UTC)
                }
            }
            assertEquals(
                1,
                it.deps.retentionJob
                    .run()
                    .claimFilesDeleted,
            )
            assertNull(store.objects[document])
            assertTrue(TestInfra.database.query { BusinessClaimDocuments.selectAll().toList() }.isEmpty())
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.verifiedClaim(token: String): String {
        val claim = client.startClaim(token).claim(HttpStatusCode.Created)
        val code = server.otp.lastCodeFor(businessNumber)
        assertEquals(
            "verified",
            client.postJson("/v1/business/claims/${claim.id}/code", """{"code":"$code"}""", bearer(token)).claim().status,
        )
        return claim.id
    }

    // Uploads one PDF and submits it; returns its key.
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.submitWithDocument(
        token: String,
        id: String,
    ): String {
        val key = client.upload(token, id).uploadKey()
        store.upload(key, ByteArray(2_000), "application/pdf")
        assertEquals("submitted", client.submit(token, id, key).claim().status)
        return key
    }
}

/** A claim body for the test business. */
internal fun claimJson(
    number: String = "+8801812345678",
    category: String = "bank",
    method: String = "sms",
    website: String = "https://bank.example",
) = """{"e164":"$number","legalName":"Example Bank Ltd","displayName":"Example Bank","category":"$category",""" +
    """"website":"$website","hours":"Sun–Thu 9–17","callReason":"Account security","method":"$method"}"""

internal suspend fun HttpClient.startClaim(
    token: String,
    number: String = "+8801812345678",
    category: String = "bank",
    method: String = "sms",
): HttpResponse = postJson("/v1/business/claims", claimJson(number, category, method), bearer(token))

internal suspend fun HttpClient.upload(
    token: String,
    id: String,
    kind: String = "document",
    contentType: String = "application/pdf",
): HttpResponse = postJson("/v1/business/claims/$id/uploads", """{"kind":"$kind","contentType":"$contentType"}""", bearer(token))

internal suspend fun HttpClient.submit(
    token: String,
    id: String,
    document: String,
    logo: String? = null,
): HttpResponse {
    val logoJson = logo?.let { ""","logoKey":"$it"""" }.orEmpty()
    return postJson("/v1/business/claims/$id/submit", """{"documentKeys":["$document"]$logoJson}""", bearer(token))
}

internal suspend fun HttpResponse.claim(expected: HttpStatusCode = HttpStatusCode.OK): ClaimResponse {
    assertEquals(expected, status, bodyAsText())
    assertEquals("no-store", headers[HttpHeaders.CacheControl])
    return testJson.decodeFromString(ClaimResponse.serializer(), bodyAsText())
}

internal suspend fun HttpResponse.claims(): List<ClaimResponse> {
    assertEquals(HttpStatusCode.OK, status, bodyAsText())
    return testJson.decodeFromString(ClaimsResponse.serializer(), bodyAsText()).claims
}

internal suspend fun HttpResponse.uploadKey(): String {
    assertEquals(HttpStatusCode.OK, status, bodyAsText())
    return testJson.decodeFromString(ClaimUploadResponse.serializer(), bodyAsText()).key
}
