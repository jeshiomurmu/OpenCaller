package io.github.jeshiomurmu.opencaller.server.security

import io.github.jeshiomurmu.opencaller.server.db.Users
import io.github.jeshiomurmu.opencaller.server.profile.MeResponse
import io.github.jeshiomurmu.opencaller.server.testing.TestInfra
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.getWith
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.putJson
import io.github.jeshiomurmu.opencaller.server.testing.signIn
import io.github.jeshiomurmu.opencaller.server.testing.testJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * SQL injection payloads at the endpoints that take free text or path segments (docs/security.md, MASVS-CODE-4 and
 * the server checklist). Every query is built by Exposed with bound parameters, and the few raw statements are
 * constants; these tests check the outside: payloads are refused as input errors (never a 500) or stored verbatim
 * as text, and the tables are untouched.
 */
class SqlInjectionTest {
    private val number = "+8801712345678"

    private suspend fun userCount() = TestInfra.database.query { Users.selectAll().count() }

    @Test
    fun payloadsInNumbersAreInputErrors() =
        apiTest { server ->
            val token = signIn(server, number).accessToken
            val users = userCount()
            for (payload in PAYLOADS) {
                val search = client.getWith("/v1/numbers/${payload.encodeURLPathPart()}", token)
                assertEquals(HttpStatusCode.BadRequest, search.status, "search $payload: ${search.bodyAsText()}")
                val start = client.postJson("/v1/auth/start", """{"e164":${JsonPrimitive(payload)}}""")
                assertEquals(HttpStatusCode.BadRequest, start.status, "auth $payload: ${start.bodyAsText()}")
                val bucket = client.getWith("/v1/lookup/buckets/880/${payload.encodeURLPathPart()}")
                assertTrue(
                    bucket.status == HttpStatusCode.BadRequest || bucket.status == HttpStatusCode.NotFound,
                    "bucket $payload: ${bucket.status}",
                )
            }
            assertEquals(users, userCount())
        }

    @Test
    fun aPayloadAsADisplayNameIsJustText() =
        apiTest { server ->
            val token = signIn(server, number).accessToken
            val users = userCount()
            for (payload in NAMES) {
                val response =
                    client.putJson(
                        "/v1/me",
                        """{"displayName":${JsonPrimitive(payload)},"visibility":"everyone","incognito":false,"avatarKey":null}""",
                        token,
                    )
                assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
                val me = testJson.decodeFromString(MeResponse.serializer(), client.getWith("/v1/me", token).bodyAsText())
                assertEquals(payload, me.displayName)
            }
            assertEquals(users, userCount())
        }

    private companion object {
        val PAYLOADS =
            listOf(
                "' OR '1'='1",
                "+880' OR 1=1 --",
                "+8801712345678'; DROP TABLE users; --",
                "1; DELETE FROM reports",
                "+8801712345678\" OR \"\"=\"",
                "') UNION SELECT e164 FROM users --",
            )

        // Within the profile's rules (1–60 characters, one line), so they are accepted and stored as text.
        val NAMES =
            listOf(
                "Robert'); DROP TABLE users;--",
                "x' OR '1'='1",
                "\\'; SELECT pg_sleep(5); --",
            )
    }
}
