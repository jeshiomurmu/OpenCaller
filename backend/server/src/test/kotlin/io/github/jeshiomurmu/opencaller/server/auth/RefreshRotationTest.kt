package io.github.jeshiomurmu.opencaller.server.auth

import com.auth0.jwt.JWT
import io.github.jeshiomurmu.opencaller.server.db.RefreshTokens
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** TC-API-003: rotation, reuse detection (the device's chain is revoked) and expiry. */
class RefreshRotationTest {
    private val number = "+12015550123"

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.refresh(token: String) =
        client.postJson("/v1/auth/refresh", """{"refreshToken":"$token"}""")

    private fun deviceOf(accessToken: String): UUID = UUID.fromString(JWT.decode(accessToken).getClaim("did").asString())

    @Test
    fun tcApi003OldRefreshTokenIsRejectedAfterRotation() =
        apiTest { server ->
            val first = signIn(server, number)
            val rotated = refresh(first.refreshToken)
            assertEquals(HttpStatusCode.OK, rotated.status, rotated.bodyAsText())
            val second = testJson.decodeFromString(TokenResponse.serializer(), rotated.bodyAsText())
            assertNotEquals(first.refreshToken, second.refreshToken)
            assertEquals(JWT.decode(first.accessToken).subject, JWT.decode(second.accessToken).subject)
            assertEquals(deviceOf(first.accessToken), deviceOf(second.accessToken))

            refresh(first.refreshToken).assertProblem(HttpStatusCode.Unauthorized, "refresh_token_reused")
        }

    @Test
    fun reusingARotatedTokenRevokesTheWholeChainOfThatDevice() =
        apiTest { server ->
            val first = signIn(server, number)
            val second = testJson.decodeFromString(TokenResponse.serializer(), refresh(first.refreshToken).bodyAsText())
            val device = deviceOf(first.accessToken)

            refresh(first.refreshToken).assertProblem(HttpStatusCode.Unauthorized, "refresh_token_reused")
            // The token issued by the rotation is gone too: the thief and the owner both have to sign in again.
            refresh(second.refreshToken).assertProblem(HttpStatusCode.Unauthorized, "invalid_refresh_token")
            assertEquals(0L, TestInfra.database.query { RefreshTokens.selectAll().where { RefreshTokens.deviceId eq device }.count() })
        }

    @Test
    fun otherDevicesKeepTheirTokens() =
        apiTest { server ->
            val phone = signIn(server, number)
            val tablet = signIn(server, number)
            refresh(phone.refreshToken)
            refresh(phone.refreshToken).assertProblem(HttpStatusCode.Unauthorized, "refresh_token_reused")
            assertEquals(HttpStatusCode.OK, refresh(tablet.refreshToken).status)
        }

    @Test
    fun rotatedTokensKeepWorkingInSequence() =
        apiTest { server ->
            var token = signIn(server, number).refreshToken
            repeat(3) {
                val response = refresh(token)
                assertEquals(HttpStatusCode.OK, response.status)
                token = testJson.decodeFromString(TokenResponse.serializer(), response.bodyAsText()).refreshToken
            }
            val rows = TestInfra.database.query { RefreshTokens.selectAll().toList() }
            assertEquals(4, rows.size)
            assertEquals(1, rows.count { it[RefreshTokens.rotatedAt] == null })
            rows.filter { it[RefreshTokens.rotatedAt] != null }.forEach { assertNotNull(it[RefreshTokens.expiresAt]) }
        }

    @Test
    fun refreshTokensExpireAfterThirtyDays() =
        apiTest { server ->
            val tokens = signIn(server, number)
            server.clock.advance(30.days + 1.seconds)
            refresh(tokens.refreshToken).assertProblem(HttpStatusCode.Unauthorized, "invalid_refresh_token")
        }

    @Test
    fun unknownOrMalformedTokensAreRejected() =
        apiTest {
            refresh("not-a-token").assertProblem(HttpStatusCode.Unauthorized, "invalid_refresh_token")
            refresh("").assertProblem(HttpStatusCode.Unauthorized, "invalid_refresh_token")
            refresh("x".repeat(500)).assertProblem(HttpStatusCode.Unauthorized, "invalid_refresh_token")
        }
}
