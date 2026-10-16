package io.github.jeshiomurmu.opencaller.server.auth

import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** TC-API-001 and the per-IP limit (docs/rules.md §7: OTP start 3/hour/number, 10/hour/IP). */
class RateLimitTest {
    @Test
    fun tcApi001FourthStartForTheSameNumberWithinAnHourIs429WithRetryAfter() =
        apiTest { server ->
            val number = "+12015550123"
            repeat(3) { assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/auth/start", """{"e164":"$number"}""").status) }

            val fourth = client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            val problem = fourth.assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
            val retryAfter = fourth.headers[HttpHeaders.RetryAfter]!!.toLong()
            assertTrue(retryAfter in 3500..3600, "Retry-After $retryAfter")
            assertTrue(problem.detail!!.contains("$retryAfter seconds"))
            assertEquals(3, server.otp.sent.size)
        }

    @Test
    fun theLimitIsPerNumber() =
        apiTest {
            repeat(3) { client.postJson("/v1/auth/start", """{"e164":"+12015550123"}""") }
            assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/auth/start", """{"e164":"+12015550124"}""").status)
        }

    @Test
    fun eleventhStartFromOneAddressWithinAnHourIs429() =
        apiTest { server ->
            (0 until 10).forEach { i ->
                assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/auth/start", """{"e164":"+1201555013$i"}""").status)
            }
            val eleventh = client.postJson("/v1/auth/start", """{"e164":"+12015550140"}""")
            eleventh.assertProblem(HttpStatusCode.TooManyRequests, "rate_limited")
            assertTrue(eleventh.headers[HttpHeaders.RetryAfter]!!.toLong() in 1..3600)
            assertEquals(10, server.otp.sent.size)
        }
}
