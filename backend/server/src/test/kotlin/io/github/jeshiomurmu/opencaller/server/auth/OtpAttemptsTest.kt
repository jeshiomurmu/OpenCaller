package io.github.jeshiomurmu.opencaller.server.auth

import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpCheck
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpService
import io.github.jeshiomurmu.opencaller.server.auth.otp.OtpStore
import io.github.jeshiomurmu.opencaller.server.testing.RecordingOtpProvider
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** TC-API-002 and the other code rules: 6 digits, single use, 5-minute lifetime, 5 attempts. */
class OtpAttemptsTest {
    private val number = "+12015550123"

    private fun wrongCodeFor(right: String) = if (right == "000000") "000001" else "000000"

    @Test
    fun tcApi002FiveWrongCodesInvalidateTheCode() =
        apiTest { server ->
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            val right = server.otp.lastCodeFor(number)
            val wrong = wrongCodeFor(right)
            (4 downTo 1).forEach { left ->
                val problem =
                    client
                        .postJson(
                            "/v1/auth/verify",
                            """{"e164":"$number","code":"$wrong"}""",
                        ).assertProblem(HttpStatusCode.BadRequest, "invalid_code")
                assertEquals("$left attempts left.", problem.detail)
            }
            client
                .postJson(
                    "/v1/auth/verify",
                    """{"e164":"$number","code":"$wrong"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "code_invalidated")
            // The right code no longer works either.
            client
                .postJson(
                    "/v1/auth/verify",
                    """{"e164":"$number","code":"$right"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "code_expired")

            // A new code does.
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            assertEquals(
                HttpStatusCode.OK,
                client.postJson("/v1/auth/verify", """{"e164":"$number","code":"${server.otp.lastCodeFor(number)}"}""").status,
            )
        }

    @Test
    fun aCodeWorksOnlyOnce() =
        apiTest { server ->
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            val code = server.otp.lastCodeFor(number)
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/verify", """{"e164":"$number","code":"$code"}""").status)
            client
                .postJson(
                    "/v1/auth/verify",
                    """{"e164":"$number","code":"$code"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "code_expired")
        }

    @Test
    fun aNewCodeReplacesTheOldOne() =
        apiTest { server ->
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            val first = server.otp.lastCodeFor(number)
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            val second = server.otp.lastCodeFor(number)
            if (first != second) {
                client
                    .postJson(
                        "/v1/auth/verify",
                        """{"e164":"$number","code":"$first"}""",
                    ).assertProblem(HttpStatusCode.BadRequest, "invalid_code")
            }
            assertEquals(HttpStatusCode.OK, client.postJson("/v1/auth/verify", """{"e164":"$number","code":"$second"}""").status)
        }

    @Test
    fun malformedCodesAreRejectedWithoutUsingAnAttempt() =
        apiTest { server ->
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            listOf("12345", "1234567", "12a456", "", "１２３４５６").forEach { code ->
                client
                    .postJson(
                        "/v1/auth/verify",
                        """{"e164":"$number","code":"$code"}""",
                    ).assertProblem(HttpStatusCode.BadRequest, "invalid_code_format")
            }
            val wrong = wrongCodeFor(server.otp.lastCodeFor(number))
            val problem =
                client
                    .postJson(
                        "/v1/auth/verify",
                        """{"e164":"$number","code":"$wrong"}""",
                    ).assertProblem(HttpStatusCode.BadRequest, "invalid_code")
            assertEquals("4 attempts left.", problem.detail)
        }

    @Test
    fun codesAreSixDigitsAndExpire() =
        runBlocking {
            TestInfra.redis.evalLong("redis.call('FLUSHALL') return 1", emptyList())
            val provider = RecordingOtpProvider()
            val service = OtpService(OtpStore(TestInfra.redis), provider, "p".repeat(32), SecureRandom(), validFor = 1.seconds)
            val e164 = E164.parseOrNull(number)!!
            service.start(e164)
            val code = provider.lastCodeFor(number)
            assertEquals(6, code.length)
            assertEquals(true, OtpService.isWellFormed(code))
            delay(2_100.milliseconds)
            assertEquals(OtpCheck.Missing, service.verify(e164, code))
        }
}
