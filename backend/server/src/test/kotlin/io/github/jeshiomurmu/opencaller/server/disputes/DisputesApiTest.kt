package io.github.jeshiomurmu.opencaller.server.disputes

import io.github.jeshiomurmu.opencaller.server.db.Disputes
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.bearer
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals

/** `POST /v1/disputes`. */
class DisputesApiTest {
    @Test
    fun aDisputeIsQueuedForReview() =
        apiTest { server ->
            val token = bearer(signIn(server, "+8801712345678").accessToken)
            val response = client.postJson("/v1/disputes", """{"e164":"+8801812345678","reason":"  This is my shop's number.  "}""", token)
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            assertEquals("""{"id":1,"status":"pending"}""", response.bodyAsText())
            val row = TestInfra.database.query { Disputes.selectAll().single() }
            assertEquals("+8801812345678", row[Disputes.e164])
            assertEquals("This is my shop's number.", row[Disputes.reason])
            assertEquals(ReviewStatus.PENDING, row[Disputes.status])
        }

    @Test
    fun invalidDisputesAreRejected() =
        apiTest { server ->
            val token = bearer(signIn(server, "+8801712345678").accessToken)
            client
                .postJson(
                    "/v1/disputes",
                    """{"e164":"123","reason":"x"}""",
                    token,
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            client
                .postJson(
                    "/v1/disputes",
                    """{"e164":"+8801812345678","reason":"  "}""",
                    token,
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client
                .postJson("/v1/disputes", """{"e164":"+8801812345678","reason":"${"x".repeat(1001)}"}""", token)
                .assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client
                .postJson(
                    "/v1/disputes",
                    """{"e164":"+8801812345678","reason":"x"}""",
                ).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    @Test
    fun disputesAreLimitedPerDay() =
        apiTest { server ->
            val token = bearer(signIn(server, "+8801712345678").accessToken)
            repeat(10) {
                assertEquals(
                    HttpStatusCode.Created,
                    client.postJson("/v1/disputes", """{"e164":"+8801812345678","reason":"r"}""", token).status,
                )
            }
            client
                .postJson(
                    "/v1/disputes",
                    """{"e164":"+8801812345678","reason":"r"}""",
                    token,
                ).assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
        }
}
