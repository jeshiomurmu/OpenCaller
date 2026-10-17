package io.github.jeshiomurmu.opencaller.server.unlist

import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.UnlistRequests
import io.github.jeshiomurmu.opencaller.server.db.Visibility
import io.github.jeshiomurmu.opencaller.server.lookup.BucketResponse
import io.github.jeshiomurmu.opencaller.server.search.SearchResponse
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.insertBusiness
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.insertUser
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unlisting through the API and the HTML form (TC-API-010). */
class UnlistTest {
    private val owner = "+8801712345678"

    // TC-API-010: a verified unlist removes the name within one job run; the number keeps its spam score.
    @Test
    fun verifiedUnlistRemovesTheNameButKeepsTheScore() =
        apiTest { server ->
            insertUser(owner, "Owner Name", Visibility.EVERYONE)
            insertReports(owner, 4, at = server.clock.now)
            server.deps.scoringJob.run()
            val token = signIn(server, "+12015550123").accessToken

            suspend fun bucketName() =
                testJson
                    .decodeFromString(BucketResponse.serializer(), client.getWith("/v1/lookup/buckets/880/25856").bodyAsText())
                    .entries
                    .single()
                    .info

            suspend fun searchInfo() =
                testJson.decodeFromString(SearchResponse.serializer(), client.getWith("/v1/numbers/$owner", token).bodyAsText()).info

            assertEquals("Owner Name", bucketName().name)
            val scoreBefore = bucketName().spamScore

            assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/unlist/start", """{"e164":"$owner"}""").status)
            val verify = client.postJson("/v1/unlist/verify", """{"e164":"$owner","code":"${server.otp.lastCodeFor(owner)}"}""")
            assertEquals(HttpStatusCode.Accepted, verify.status)
            assertEquals("""{"status":"verified","processedWithinMinutes":15}""", verify.bodyAsText())
            // Takes effect with the next job run.
            assertEquals("Owner Name", bucketName().name)
            assertEquals(
                1,
                server.deps.scoringJob
                    .run()
                    .unlistProcessed,
            )

            assertNull(bucketName().name)
            assertEquals(scoreBefore, bucketName().spamScore)
            assertEquals(4, bucketName().reportCount)
            assertNull(searchInfo().name)
            assertEquals(4, searchInfo().reportCount)
            val reputation = TestInfra.database.query { NumberReputation.selectAll().where { NumberReputation.e164 eq owner }.single() }
            assertEquals(4, reputation[NumberReputation.reportCount])
        }

    @Test
    fun unlistingHidesABusinessName() =
        apiTest { server ->
            insertBusiness(owner, "Example Bank")
            server.deps.scoringJob.run()
            client.postJson("/v1/unlist/start", """{"e164":"$owner"}""")
            client.postJson("/v1/unlist/verify", """{"e164":"$owner","code":"${server.otp.lastCodeFor(owner)}"}""")
            server.deps.scoringJob.run()
            val bucket = testJson.decodeFromString(BucketResponse.serializer(), client.getWith("/v1/lookup/buckets/880/25856").bodyAsText())
            assertTrue(bucket.entries.isEmpty())
        }

    @Test
    fun codesAreCheckedAndSeparateFromSignInCodes() =
        apiTest { server ->
            client.postJson("/v1/unlist/start", """{"e164":"$owner"}""")
            val unlistCode = server.otp.lastCodeFor(owner)
            // A sign-in code for the same number doesn't replace or satisfy the unlist code.
            client.postJson("/v1/auth/start", """{"e164":"$owner"}""")
            val wrong = if (unlistCode == "000000") "111111" else "000000"
            client
                .postJson(
                    "/v1/unlist/verify",
                    """{"e164":"$owner","code":"$wrong"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_code")
            client
                .postJson(
                    "/v1/unlist/verify",
                    """{"e164":"$owner","code":"12"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_code_format")
            client
                .postJson(
                    "/v1/unlist/verify",
                    """{"e164":"bad","code":"123456"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/unlist/verify", """{"e164":"$owner","code":"$unlistCode"}""").status)
            client
                .postJson("/v1/unlist/verify", """{"e164":"$owner","code":"$unlistCode"}""")
                .assertProblem(HttpStatusCode.BadRequest, "code_expired")
            assertEquals(1L, TestInfra.database.query { UnlistRequests.selectAll().count() })
        }

    @Test
    fun startIsRateLimitedLikeSignIn() =
        apiTest {
            repeat(3) { assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/unlist/start", """{"e164":"$owner"}""").status) }
            val refused = client.postJson("/v1/unlist/start", """{"e164":"$owner"}""")
            refused.assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
            assertNotNull(refused.headers[HttpHeaders.RetryAfter])
            client.postJson("/v1/unlist/start", """{"e164":"12345"}""").assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
        }

    @Test
    fun theHtmlFormWorksWithoutTheApp() =
        apiTest { server ->
            val form = client.getWith("/unlist")
            assertEquals(HttpStatusCode.OK, form.status)
            assertEquals(ContentType.Text.Html, form.contentType()?.withoutParameters())
            assertTrue(form.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'none'"))
            assertTrue(form.bodyAsText().contains("<form method=\"post\" action=\"/unlist\">"))

            // People type numbers with spaces and dashes.
            val codePage = client.submitForm("/unlist", parameters { append("e164", "+880 1712-345678") })
            assertEquals(HttpStatusCode.OK, codePage.status, codePage.bodyAsText())
            assertTrue(codePage.bodyAsText().contains("action=\"/unlist/verify\""))
            val code = server.otp.lastCodeFor(owner)

            val wrong =
                client.submitForm(
                    "/unlist/verify",
                    parameters {
                        append("e164", "+880 1712-345678")
                        append(
                            "code",
                            "999999".takeIf { it != code } ?: "999998",
                        )
                    },
                )
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertTrue(wrong.bodyAsText().contains("Wrong code"))

            val done =
                client.submitForm(
                    "/unlist/verify",
                    parameters {
                        append("e164", "+880 1712-345678")
                        append("code", code)
                    },
                )
            assertEquals(HttpStatusCode.OK, done.status, done.bodyAsText())
            assertTrue(done.bodyAsText().contains("will be unlisted within 15 minutes"))
            val request = TestInfra.database.query { UnlistRequests.selectAll().single() }
            assertEquals(owner, request[UnlistRequests.e164])
            assertNotNull(request[UnlistRequests.verifiedAt])
        }

    @Test
    fun theHtmlFormShowsErrorsAndEscapesInput() =
        apiTest {
            val page = client.submitForm("/unlist", parameters { append("e164", "<script>alert(1)</script>") })
            assertEquals(HttpStatusCode.BadRequest, page.status)
            val html = page.bodyAsText()
            assertTrue(html.contains("Not a valid phone number"))
            assertFalse(html.contains("<script>"))
            assertEquals("&lt;b&gt; &amp; &quot;x&quot; &#39;y&#39;", UnlistPages.escape("<b> & \"x\" 'y'"))
        }
}
