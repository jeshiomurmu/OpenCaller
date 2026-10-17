package io.github.jeshiomurmu.opencaller.server.reports

import io.github.jeshiomurmu.opencaller.server.db.ReportLabel
import io.github.jeshiomurmu.opencaller.server.db.ReporterTrust
import io.github.jeshiomurmu.opencaller.server.db.Reports
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /v1/reports`: validation (TC-API-004), batch size, trust default, daily limit. */
class ReportsApiTest {
    private val reporter = "+8801712345678"

    private fun batch(vararg items: String) = """{"reports":[${items.joinToString(",")}]}"""

    private fun spam(
        e164: String,
        extra: String = "",
    ) = """{"e164":"$e164","label":"spam"$extra}"""

    @Test
    fun reportsAreStoredWithTheDefaultTrust() =
        apiTest { server ->
            val token = signIn(server, reporter).accessToken
            val response =
                client.postJson(
                    "/v1/reports",
                    batch(
                        spam("+12015550123", ""","category":"telemarketing","comment":"  Calls every day  """"),
                        """{"e164":"+918123456789","label":"not_spam"}""",
                    ),
                    bearer(token),
                )
            assertEquals(HttpStatusCode.Accepted, response.status, response.bodyAsText())
            assertEquals("""{"accepted":2}""", response.bodyAsText())
            val rows = TestInfra.database.query { Reports.selectAll().orderBy(Reports.id).toList() }
            assertEquals(listOf("+12015550123", "+918123456789"), rows.map { it[Reports.e164] })
            assertEquals(listOf(ReportLabel.SPAM, ReportLabel.NOT_SPAM), rows.map { it[Reports.label] })
            assertEquals("telemarketing", rows[0][Reports.category])
            assertEquals("Calls every day", rows[0][Reports.comment])
            assertNull(rows[1][Reports.comment])
            assertEquals(server.clock.now, rows[0][Reports.createdAt].toInstant())
            val trust = TestInfra.database.query { ReporterTrust.selectAll().single() }
            assertEquals(0.5f, trust[ReporterTrust.trust])
        }

    // TC-API-004 on /v1/reports: an invalid E.164 number → 400 problem+json, nothing stored.
    @Test
    fun invalidNumbersAreRejected() =
        apiTest { server ->
            val token = signIn(server, reporter).accessToken
            listOf("8801712345678", "+880 1712 345678", "+1", "+999123456789", "+12015550123x", "").forEach { bad ->
                val problem =
                    client
                        .postJson("/v1/reports", batch(spam("+12015550123"), spam(bad)), bearer(token))
                        .assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
                assertTrue(problem.detail!!.contains("reports[1].e164"), problem.detail)
            }
            assertEquals(0L, TestInfra.database.query { Reports.selectAll().count() })
        }

    @Test
    fun otherInvalidFieldsAreRejected() =
        apiTest { server ->
            val token = bearer(signIn(server, reporter).accessToken)
            val bad =
                listOf(
                    batch(),
                    batch(*Array(51) { spam("+12015550123") }),
                    batch("""{"e164":"+12015550123","label":"maybe"}"""),
                    batch(spam("+12015550123", ""","category":"aliens"""")),
                    batch("""{"e164":"+12015550123","label":"not_spam","category":"scam"}"""),
                    batch(spam("+12015550123", ""","comment":"${"x".repeat(281)}"""")),
                    batch(spam("+12015550123", ""","comment":"bell\u0007"""")),
                    """{"reports":"nope"}""",
                )
            bad.forEach { body -> client.postJson("/v1/reports", body, token).assertProblem(HttpStatusCode.BadRequest, "invalid_request") }
            assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/reports", batch(*Array(50) { spam("+12015550123") }), token).status)
        }

    @Test
    fun anAccessTokenIsRequired() =
        apiTest {
            client.postJson("/v1/reports", batch(spam("+12015550123"))).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            client
                .postJson("/v1/reports", batch(spam("+12015550123")), bearer("not-a-token"))
                .assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    // docs/rules.md §7: reports 100/day per user, counted per report.
    @Test
    fun theDailyLimitCountsReports() =
        apiTest { server ->
            val token = bearer(signIn(server, reporter).accessToken)
            repeat(2) {
                assertEquals(
                    HttpStatusCode.Accepted,
                    client.postJson("/v1/reports", batch(*Array(50) { spam("+12015550123") }), token).status,
                )
            }
            val refused = client.postJson("/v1/reports", batch(spam("+12015550123")), token)
            refused.assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
            assertTrue(refused.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..86_400)
            assertEquals(100L, TestInfra.database.query { Reports.selectAll().count() })
            // Another user has their own allowance.
            val other = bearer(signIn(server, "+8801812345678").accessToken)
            assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/reports", batch(spam("+12015550123")), other).status)
        }
}
