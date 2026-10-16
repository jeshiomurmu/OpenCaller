package io.github.jeshiomurmu.opencaller.server.auth

import com.auth0.jwt.JWT
import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.db.Devices
import io.github.jeshiomurmu.opencaller.server.db.RefreshTokens
import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testConfig
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.selectAll
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** Start → verify → refresh, access tokens, hashed storage and delivery failures. */
class AuthFlowTest {
    private val number = "+12015550123"

    @Test
    fun signInIssuesTokensForANewUserAndDevice() =
        apiTest { server ->
            val start = client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            assertEquals(HttpStatusCode.Accepted, start.status)
            assertEquals("""{"expiresInSeconds":300}""", start.bodyAsText())
            assertEquals(1, server.otp.sent.size)

            val tokens = signIn(server, number)
            assertEquals(900, tokens.accessTokenExpiresIn)
            assertEquals(30L * 24 * 3600, tokens.refreshTokenExpiresIn)
            assertEquals("Bearer", tokens.tokenType)

            val jwt = JWT.decode(tokens.accessToken)
            val (userId, deviceId, flavor) =
                TestInfra.database.query {
                    val user = Users.selectAll().single()
                    val device = Devices.selectAll().single()
                    Triple(user[Users.id], device[Devices.id], device[Devices.flavor])
                }
            assertEquals(userId.toString(), jwt.subject)
            assertEquals(deviceId.toString(), jwt.getClaim(TokenService.DEVICE_CLAIM).asString())
            assertEquals("foss", flavor)
            assertEquals("opencaller", jwt.issuer)
            assertEquals(listOf("opencaller-app"), jwt.audience)
        }

    @Test
    fun signingInAgainKeepsTheUserAndAddsADevice() =
        apiTest { server ->
            val first = JWT.decode(signIn(server, number).accessToken)
            val second = JWT.decode(signIn(server, number).accessToken)
            assertEquals(first.subject, second.subject)
            assertNotEquals(first.getClaim("did").asString(), second.getClaim("did").asString())
            assertEquals(1L, TestInfra.database.query { Users.selectAll().count() })
            assertEquals(2L, TestInfra.database.query { Devices.selectAll().count() })
        }

    @Test
    fun refreshTokensAreStoredOnlyAsHashes() =
        apiTest { server ->
            val tokens = signIn(server, number)
            val stored = TestInfra.database.query { RefreshTokens.selectAll().single()[RefreshTokens.tokenHash] }
            assertEquals(TokenService.hash(tokens.refreshToken), stored)
            assertNotEquals(tokens.refreshToken, stored)
            assertTrue(stored.matches(Regex("[0-9a-f]{64}")))
        }

    @Test
    fun codesAreStoredOnlyAsKeyedHashesWithAFiveMinuteLifetime() =
        apiTest { server ->
            client.postJson("/v1/auth/start", """{"e164":"$number"}""")
            val code = server.otp.lastCodeFor(number)
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(testConfig().otp.pepper.toByteArray(), "HmacSHA256")) }
            val expected = mac.doFinal("$number:$code".toByteArray()).toHexString()
            assertEquals(expected, TestInfra.redis.hget("otp:$number", "h"))
            assertTrue(TestInfra.redis.ttl("otp:$number") in 290..300)
        }

    @Test
    fun accessTokensProtectRoutesAndExpireAfterFifteenMinutes() =
        apiTest { server ->
            application {
                routing {
                    authenticate(ACCESS_AUTH) {
                        get("/test/me") { call.respondText(call.principal<JWTPrincipal>()!!.subject!!) }
                    }
                }
            }
            val tokens = signIn(server, number)
            val ok = client.get("/test/me") { bearerAuth(tokens.accessToken) }
            assertEquals(HttpStatusCode.OK, ok.status)
            assertEquals(JWT.decode(tokens.accessToken).subject, ok.bodyAsText())

            client.get("/test/me").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            client.get("/test/me") { bearerAuth(tokens.accessToken + "x") }.assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            server.clock.advance(15.minutes)
            client.get("/test/me") { bearerAuth(tokens.accessToken) }.assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
        }

    @Test
    fun aCodeThatCannotBeDeliveredIsNotKept() =
        apiTest { server ->
            server.otp.failNext = true
            client
                .postJson(
                    "/v1/auth/start",
                    """{"e164":"$number"}""",
                ).assertProblem(HttpStatusCode.ServiceUnavailable, "otp_delivery_failed")
            assertEquals(0L, TestInfra.redis.evalLong("return redis.call('EXISTS', KEYS[1])", listOf("otp:$number")))
        }
}
