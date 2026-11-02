package io.github.jeshiomurmu.opencaller.server.business

import io.github.jeshiomurmu.opencaller.server.lookup.BucketResponse
import io.github.jeshiomurmu.opencaller.server.lookup.BusinessInfo
import io.github.jeshiomurmu.opencaller.server.lookup.NumberInfo
import io.github.jeshiomurmu.opencaller.server.lookup.NumberKey
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Approval publishes a business; revoking takes it back (docs/prd.md F-BZ-01/02, TC-BIZ-002 on the server). */
class BusinessPublishingTest {
    private val store = InMemoryObjectStore()
    private val server = TestServer(objectStore = store)
    private val claimant = "+8801712345678"
    private val businessNumber = "+8801812345678"
    private val logoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)

    @Test
    fun tcBiz002_anApprovedBusinessShowsItsNameLogoAndCallReasonEverywhere() =
        apiTest(server) {
            // Reported as spam before it was claimed.
            insertReports(businessNumber, 30)
            it.deps.scoringJob.run()
            assertTrue(checkNotNull(client.lookup()?.spamScore) > 0.6)

            val (claimId, token) = submittedClaimWithLogo()
            val outcome = assertIs<ReviewOutcome.Approved>(it.deps.claimReview.approve(UUID.fromString(claimId), "admin"))
            val id = outcome.businessId.toString()
            val logoPath = "/v1/business/$id/logo"

            // Bucket lookups show it at once.
            val expected =
                BusinessInfo("Example Bank", "bank", verified = true, callReason = "Account security", businessId = id, logoUrl = logoPath)
            assertEquals(expected, client.lookup()?.business)
            // The next scoring run caps the score below every threshold, which takes it off the spam lists.
            it.deps.scoringJob.run()
            assertTrue(checkNotNull(client.lookup()?.spamScore) <= 0.39)

            val profile = client.getWith("/v1/business/$id")
            assertEquals(HttpStatusCode.OK, profile.status, profile.bodyAsText())
            assertEquals("public, max-age=3600", profile.headers[HttpHeaders.CacheControl])
            val body = testJson.decodeFromString(BusinessProfileResponse.serializer(), profile.bodyAsText())
            assertEquals("Example Bank", body.name)
            assertEquals("https://bank.example", body.website)
            assertEquals("Sun–Thu 9–17", body.hours)
            assertEquals(logoPath, body.logoUrl)
            assertEquals(listOf(BusinessNumberDto(businessNumber, "Account security")), body.numbers)

            val logo = client.getWith(logoPath)
            assertEquals(HttpStatusCode.OK, logo.status)
            assertEquals("image/png", logo.headers[HttpHeaders.ContentType])
            assertContentEquals(logoBytes, logo.readRawBytes())

            val directory = client.directory()
            assertEquals(
                listOf(DirectoryEntryDto(businessNumber, id, "Example Bank", "bank", "Account security", logoPath, directory.syncedAt)),
                directory.entries,
            )
            assertTrue(client.directory(directory.syncedAt).entries.isEmpty())

            val mine = client.getWith("/v1/business/claims", token).claims().single()
            assertEquals("approved", mine.status)
            assertEquals(id, mine.businessId)
            assertIs<ReviewOutcome.Refused>(it.deps.claimReview.approve(UUID.fromString(claimId), "admin"))
            client.startClaim(token).assertProblem(HttpStatusCode.Conflict, "already_verified")
        }

    @Test
    fun aRevokedBusinessDisappearsAndAppsAreToldToForgetIt() =
        apiTest(server) {
            val (claimId, _) = submittedClaimWithLogo()
            val id = assertIs<ReviewOutcome.Approved>(it.deps.claimReview.approve(UUID.fromString(claimId), "admin")).businessId
            val before = client.directory()
            it.clock.advance(kotlin.time.Duration.parse("1s"))

            assertTrue(it.deps.claimReview.revoke(id))
            assertNull(client.lookup()?.business)
            client.getWith("/v1/business/$id").assertProblem(HttpStatusCode.NotFound, "business_not_found")
            client.getWith("/v1/business/$id/logo").assertProblem(HttpStatusCode.NotFound, "business_not_found")
            val removed = client.directory(before.syncedAt).entries.single()
            assertEquals(businessNumber, removed.e164)
            assertTrue(removed.removed)
            assertTrue(!it.deps.claimReview.revoke(id))
        }

    @Test
    fun directoryAndProfileInputsAreChecked() =
        apiTest(server) {
            client.getWith("/v1/businesses/ZZ").assertProblem(HttpStatusCode.BadRequest, "invalid_country")
            client.getWith("/v1/businesses/BD?since=yesterday").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.getWith("/v1/business/not-a-uuid").assertProblem(HttpStatusCode.NotFound, "business_not_found")
            client.getWith("/v1/business/${UUID.randomUUID()}").assertProblem(HttpStatusCode.NotFound, "business_not_found")
            assertTrue(client.directory().entries.isEmpty())
        }

    private suspend fun ApplicationTestBuilder.submittedClaimWithLogo(): Pair<String, String> {
        val token = signIn(server, claimant).accessToken
        val id = client.startClaim(token).claim(HttpStatusCode.Created).id
        val code = server.otp.lastCodeFor(businessNumber)
        client.postJson("/v1/business/claims/$id/code", """{"code":"$code"}""", bearer(token)).claim()
        val document = client.upload(token, id).uploadKey()
        store.upload(document, ByteArray(3_000), "application/pdf")
        val logo = client.upload(token, id, kind = "logo", contentType = "image/png").uploadKey()
        store.upload(logo, logoBytes, "image/png")
        assertEquals("submitted", client.submit(token, id, document, logo).claim().status)
        return id to token
    }

    // The number's bucket entry, or null when the server has nothing to say about it.
    private suspend fun HttpClient.lookup(): NumberInfo? {
        val key = NumberKey.of(businessNumber)
        val response = getWith("/v1/lookup/buckets/${key.countryCode}/${key.bucket16}")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val entries = testJson.decodeFromString(BucketResponse.serializer(), response.bodyAsText()).entries
        assertTrue(entries.size <= 1, entries.toString())
        return entries.singleOrNull()?.info
    }

    private suspend fun HttpClient.directory(since: Long? = null): DirectoryResponse {
        val response = getWith("/v1/businesses/BD" + (since?.let { "?since=$it" } ?: ""))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return testJson.decodeFromString(DirectoryResponse.serializer(), response.bodyAsText())
    }
}
