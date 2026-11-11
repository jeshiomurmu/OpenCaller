package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.business.ClaimUploadRequest
import io.github.jeshiomurmu.opencaller.server.business.StartClaimRequest
import io.github.jeshiomurmu.opencaller.server.business.SubmitClaimRequest
import io.github.jeshiomurmu.opencaller.server.config.AdminWebConfig
import io.github.jeshiomurmu.opencaller.server.config.IpNetwork
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.module
import io.github.jeshiomurmu.opencaller.server.sync.OtpSourcesResponse
import io.github.jeshiomurmu.opencaller.server.sync.SenderIdsResponse
import io.github.jeshiomurmu.opencaller.server.testing.InMemoryObjectStore
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.insertUser
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The admin web: allowlist, sign-in, CSRF, claim review, OTP-source curation and sender-ID imports. */
class AdminWebTest {
    private val store = InMemoryObjectStore()
    private val server = TestServer(objectStore = store)
    private val config =
        AdminWebConfig(8081, "reviewer", "admin-web-password-with-at-least-32-chars", listOf(checkNotNull(IpNetwork.parse("10.0.0.0/8"))))
    private val basic = "Basic " + Base64.getEncoder().encodeToString("reviewer:${config.password}".toByteArray())

    private fun adminTest(block: suspend ApplicationTestBuilder.() -> Unit) {
        runBlocking { server.reset() }
        testApplication {
            application {
                // The test stands in for the socket peer with a header (never trusted by the real module).
                adminWebModule(config, server.deps.adminWeb) { it.request.headers["X-Test-Peer"] ?: "203.0.113.9" }
            }
            block()
        }
    }

    private suspend fun HttpClient.page(
        path: String,
        peer: String = "10.1.2.3",
        auth: String? = basic,
    ): HttpResponse =
        get(path) {
            header("X-Test-Peer", peer)
            auth?.let { header(HttpHeaders.Authorization, it) }
        }

    private suspend fun HttpClient.post(
        path: String,
        fields: Map<String, String>,
    ): HttpResponse =
        submitForm(path, parameters { fields.forEach { (name, value) -> append(name, value) } }) {
            header("X-Test-Peer", "10.1.2.3")
            header(HttpHeaders.Authorization, basic)
        }

    private suspend fun HttpClient.csrf(path: String = "/otp-sources"): String {
        val html = page(path).bodyAsText()
        return checkNotNull(Regex("name=\"csrf\" value=\"([^\"]+)\"").find(html)).groupValues[1]
    }

    @Test
    fun onlyAllowlistedAddressesWithTheAdminPasswordGetIn() =
        adminTest {
            assertEquals(HttpStatusCode.Forbidden, client.page("/", peer = "203.0.113.9").status)
            assertEquals(HttpStatusCode.Forbidden, client.page("/", peer = "localhost").status)
            val noAuth = client.page("/", auth = null)
            assertEquals(HttpStatusCode.Unauthorized, noAuth.status)
            assertTrue(noAuth.headers[HttpHeaders.WWWAuthenticate].orEmpty().startsWith("Basic realm="))
            val wrong = "Basic " + Base64.getEncoder().encodeToString("reviewer:wrong".toByteArray())
            assertEquals(HttpStatusCode.Unauthorized, client.page("/", auth = wrong).status)
            val ok = client.page("/")
            assertEquals(HttpStatusCode.OK, ok.status)
            assertTrue(ok.headers["Content-Security-Policy"].orEmpty().startsWith("default-src 'none'"))
            assertEquals("DENY", ok.headers["X-Frame-Options"])
            assertEquals("no-store", ok.headers[HttpHeaders.CacheControl])
        }

    @Test
    fun aClaimIsReviewedApprovedAndAudited() =
        adminTest {
            val claimId = submittedClaim(displayName = "<b>Bank</b>")
            val queue = client.page("/claims").bodyAsText()
            assertTrue("+8801812345678" in queue)
            // Everything a claimant typed is escaped.
            assertTrue("&lt;b&gt;Bank&lt;/b&gt;" in queue && "<b>Bank</b>" !in queue)
            val detail = client.page("/claims/$claimId").bodyAsText()
            val key = checkNotNull(Regex("file\\?key=([^\"]+)\"").find(detail)).groupValues[1]
            val file = client.page("/claims/$claimId/file?key=$key")
            assertEquals(HttpStatusCode.OK, file.status)
            assertTrue(file.headers[HttpHeaders.ContentDisposition].orEmpty().startsWith("attachment"))
            assertEquals(listOf("default-src 'none'; sandbox"), file.headers.getAll("Content-Security-Policy"))
            assertEquals(HttpStatusCode.NotFound, client.page("/claims/$claimId/file?key=claims/other/doc-x.pdf").status)

            // A form post without the page's token is refused.
            assertEquals(HttpStatusCode.Forbidden, client.post("/claims/$claimId/approve", mapOf("csrf" to "forged")).status)
            val token = client.csrf("/claims/$claimId")
            val approved = client.post("/claims/$claimId/approve", mapOf("csrf" to token))
            assertEquals(HttpStatusCode.Found, approved.status)
            assertEquals("/claims/$claimId?done=approved", approved.headers[HttpHeaders.Location])
            val business = TestInfra.database.query { Businesses.selectAll().single() }
            assertEquals(ReviewStatus.APPROVED, business[Businesses.status])
            assertTrue("+8801812345678" in client.page("/businesses").bodyAsText())

            val audit =
                server.deps.adminAudit
                    .recent()
                    .map { it.action }
            assertEquals(listOf("claim.approve", "claim.file.view"), audit)
            assertTrue(
                server.deps.adminAudit
                    .recent()
                    .all { it.admin == "reviewer" && it.ip == "10.1.2.3" },
            )
            assertTrue("claim.approve" in client.page("/audit").bodyAsText())
        }

    @Test
    fun aRejectionNeedsANote() =
        adminTest {
            val claimId = submittedClaim()
            val token = client.csrf("/claims/$claimId")
            assertEquals(
                "/claims/$claimId?done=note",
                client.post("/claims/$claimId/reject", mapOf("csrf" to token, "note" to " ")).headers[HttpHeaders.Location],
            )
            val rejected = client.post("/claims/$claimId/reject", mapOf("csrf" to token, "note" to "The document doesn't show the number."))
            assertEquals("/claims/$claimId?done=rejected", rejected.headers[HttpHeaders.Location])
            assertTrue("The document doesn&#39;t show the number." in client.page("/claims/$claimId").bodyAsText())
        }

    @Test
    fun invalidOtpSourcesAreRefusedWithTheReason() =
        adminTest {
            val token = client.csrf()
            val invalid =
                client.post(
                    "/otp-sources/app-hashes",
                    mapOf(
                        "csrf" to token,
                        "hash" to "short",
                        "package" to "org.example.app",
                        "app" to "Example",
                    ),
                )
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertTrue("11 characters" in invalid.bodyAsText())
            assertEquals(
                HttpStatusCode.BadRequest,
                client
                    .post(
                        "/otp-sources/ranges",
                        mapOf(
                            "csrf" to token,
                            "prefix" to "+44",
                            "provider" to "Too wide",
                        ),
                    ).status,
            )
            assertTrue(
                server.deps.adminAudit
                    .recent()
                    .isEmpty(),
            )
        }

    @Test
    fun otpSourcesAreCuratedAndAppsSeeRemovals() =
        adminTest {
            val token = client.csrf()
            val saved =
                client.post(
                    "/otp-sources/app-hashes",
                    mapOf(
                        "csrf" to token,
                        "hash" to "AbCdEfGhIj+",
                        "package" to "org.example.app",
                        "app" to "Example",
                    ),
                )
            assertEquals("/otp-sources?done=saved", saved.headers[HttpHeaders.Location])
            assertEquals(
                HttpStatusCode.Found,
                client
                    .post(
                        "/otp-sources/ranges",
                        mapOf(
                            "csrf" to token,
                            "prefix" to "+4479460",
                            "provider" to "Example Verify",
                        ),
                    ).status,
            )
            val before = server.deps.sync.otpSources(null)
            assertEquals(listOf("AbCdEfGhIj+"), before.appHashes.map { it.hash })

            server.clock.advance(kotlin.time.Duration.parse("1s"))
            val removed = client.post("/otp-sources/app-hashes/remove", mapOf("csrf" to token, "hash" to "AbCdEfGhIj+"))
            assertEquals("/otp-sources?done=removed", removed.headers[HttpHeaders.Location])
            val after: OtpSourcesResponse = server.deps.sync.otpSources(before.syncedAt.toString())
            assertTrue(after.appHashes.single().deleted)
            assertTrue(after.verificationRanges.isEmpty())
            assertEquals(
                listOf("otp.app-hash.remove", "otp.range.save", "otp.app-hash.save"),
                server.deps.adminAudit
                    .recent()
                    .map { it.action },
            )
        }

    @Test
    fun aCsvExtractIsImportedAndACompleteOneRemovesWhatItNoLongerLists() =
        adminTest {
            val token = client.csrf("/sender-ids")
            val first = "header,brand_name,category\nHDFCBK,\"HDFC Bank, Ltd\",bank\nAMZNIN,Amazon,shopping\nbad header!,X,bank\n"
            assertEquals(HttpStatusCode.Found, client.import(token, first, complete = false).status)
            val all = server.deps.sync.senderIds("IN", null)
            assertEquals(listOf("AMZNIN", "HDFCBK"), all.senderIds.map { it.header }.sorted())
            assertEquals("HDFC Bank, Ltd", all.senderIds.first { it.header == "HDFCBK" }.brandName)

            server.clock.advance(kotlin.time.Duration.parse("1s"))
            client.import(token, "header,brand_name,category\nHDFCBK,\"HDFC Bank, Ltd\",bank\n", complete = true)
            val changes: SenderIdsResponse = server.deps.sync.senderIds("IN", all.syncedAt.toString())
            // Only the removal is new; the unchanged row isn't sent again.
            assertEquals(listOf("AMZNIN" to true), changes.senderIds.map { it.header to it.deleted })
            val page = client.page("/sender-ids").bodyAsText()
            assertTrue("India DLT (TRAI)" in page && "csv-in-test" in page)
            assertEquals(HttpStatusCode.Forbidden, client.import("forged", first, complete = false).status)
        }

    private suspend fun HttpClient.import(
        token: String,
        csv: String,
        complete: Boolean,
    ): HttpResponse =
        submitFormWithBinaryData(
            "/sender-ids/import",
            formData {
                append("csrf", token)
                append("source", "csv-in-test")
                append("country", "in")
                if (complete) append("complete", "yes")
                append("file", csv.toByteArray(), Headers.build { append(HttpHeaders.ContentDisposition, "filename=\"in.csv\"") })
            },
        ) {
            header("X-Test-Peer", "10.1.2.3")
            header(HttpHeaders.Authorization, basic)
        }

    // A claim made through the claim service directly (the API flow is covered by BusinessClaimsTest).
    private suspend fun submittedClaim(displayName: String = "Example Bank"): UUID {
        val user = insertUser("+8801712345678")
        val claims = server.deps.claims
        val request = StartClaimRequest("+8801812345678", "Example Bank Ltd", displayName, "bank")
        val id = UUID.fromString(claims.start(user, request).id)
        claims.verify(user, id, server.otp.lastCodeFor("+8801812345678"))
        val key = claims.upload(user, id, ClaimUploadRequest("document", "application/pdf")).key
        store.upload(key, ByteArray(500), "application/pdf")
        claims.submit(user, id, SubmitClaimRequest(listOf(key)))
        return id
    }

    private suspend fun HttpClient.newCard(token: String): HttpResponse =
        post(
            "/scam-cards",
            mapOf(
                "csrf" to token,
                "country" to "bd",
                "language" to "en",
                "title" to "Fake courier calls",
                "body" to "They ask for the code we text you. Never share it.",
                "link" to "",
                "expires" to "",
            ),
        )

    @Test
    fun scamCardsStayDraftsUntilPublished() =
        adminTest {
            val token = client.csrf("/scam-cards")
            val invalid =
                client.post(
                    "/scam-cards",
                    mapOf(
                        "csrf" to token,
                        "country" to "BD",
                        "language" to "en",
                        "title" to "",
                        "body" to "x",
                    ),
                )
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals("/scam-cards?done=saved", client.newCard(token).headers[HttpHeaders.Location])
            val card =
                server.deps.scamFeed
                    .all()
                    .single()
            assertEquals("BD", card.country)
            assertTrue(
                server.deps.scamFeed
                    .feed("BD")
                    .cards
                    .isEmpty(),
            )

            val published = client.post("/scam-cards/${card.id}/publish", mapOf("csrf" to token))
            assertEquals("/scam-cards?done=published", published.headers[HttpHeaders.Location])
            assertEquals(
                listOf("Fake courier calls"),
                server.deps.scamFeed
                    .feed("BD")
                    .cards
                    .map { it.title },
            )
            assertTrue("Fake courier calls" in client.page("/scam-cards?country=BD").bodyAsText())
        }

    @Test
    fun scamCardChangesNeedTheFormTokenAndAreAudited() =
        adminTest {
            val token = client.csrf("/scam-cards")
            client.newCard(token)
            val card =
                server.deps.scamFeed
                    .all()
                    .single()
            client.post("/scam-cards/${card.id}/publish", mapOf("csrf" to token))
            assertEquals(HttpStatusCode.Forbidden, client.post("/scam-cards/${card.id}/delete", mapOf("csrf" to "forged")).status)
            val deleted = client.post("/scam-cards/${card.id}/delete", mapOf("csrf" to token))
            assertEquals("/scam-cards?done=deleted", deleted.headers[HttpHeaders.Location])
            assertTrue(
                server.deps.scamFeed
                    .all()
                    .isEmpty(),
            )
            val audit = client.page("/audit").bodyAsText()
            listOf("scam-card.save", "scam-card.publish", "scam-card.delete").forEach { assertTrue(it in audit, it) }
        }

    @Test
    fun recordingCountriesAreSwitchedOnlyWithTheLegalConfirmationAndAudited() =
        adminTest {
            val page = client.page("/recording-countries").bodyAsText()
            assertTrue("BD" in page && "IN" in page && "RECORDING_COUNTRIES" in page, page)
            val token = client.csrf("/recording-countries")

            // Switching a new country on needs the confirmation that the legal review approved it.
            val unconfirmed = client.post("/recording-countries", mapOf("csrf" to token, "country" to "NP", "enabled" to "true"))
            assertEquals("/recording-countries?done=unconfirmed", unconfirmed.headers[HttpHeaders.Location])
            assertTrue(!runBlocking { server.deps.recordingCountries.approved("NP") })
            val bad =
                client.post("/recording-countries", mapOf("csrf" to token, "country" to "Nepal", "enabled" to "true", "approved" to "yes"))
            assertEquals("/recording-countries?done=invalid", bad.headers[HttpHeaders.Location])

            client.post("/recording-countries", mapOf("csrf" to token, "country" to "np", "enabled" to "true", "approved" to "yes"))
            client.post("/recording-countries", mapOf("csrf" to token, "country" to "BD", "enabled" to "false"))
            assertTrue(runBlocking { server.deps.recordingCountries.approved("NP") })
            assertTrue(!runBlocking { server.deps.recordingCountries.approved("BD") })
            assertTrue(runBlocking { server.deps.recordingCountries.approved("IN") })
            assertEquals(HttpStatusCode.Forbidden, client.post("/recording-countries", mapOf("csrf" to "forged", "country" to "BD")).status)
            val audit = client.page("/audit").bodyAsText()
            listOf("recording-country.on", "recording-country.off").forEach { assertTrue(it in audit, it) }
        }

    @Test
    fun theApiDoesNotServeTheAdminPages() =
        testApplication {
            application { module(server.deps) }
            assertEquals(HttpStatusCode.NotFound, client.get("/claims").status)
            assertTrue(testJson.parseToJsonElement(client.get("/claims").bodyAsText()).toString().contains("not_found"))
        }
}
