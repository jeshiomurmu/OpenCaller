package io.github.jeshiomurmu.opencaller.server.http

import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.github.jeshiomurmu.opencaller.server.testing.requestIdOf
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** TC-API-004 (invalid E.164 → 400 problem+json) and the API's general error and request-ID behaviour. */
class ValidationTest {
    @Test
    fun tcApi004InvalidE164Is400ProblemJson() =
        apiTest { server ->
            // Not E.164 / not a valid number / not canonical / too short / too long / letters / blank.
            val invalid = listOf("012015550123", "12015550123", "+1 201 555 0123", "+120155501", "+1201555012345678", "+1201555O123", "")
            invalid.forEach { raw ->
                val problem =
                    client
                        .postJson(
                            "/v1/auth/start",
                            """{"e164":"$raw"}""",
                        ).assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
                assertTrue(problem.detail!!.contains("E.164"))
            }
            client
                .postJson(
                    "/v1/auth/verify",
                    """{"e164":"+999123456789","code":"123456"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_phone_number")
            assertTrue(server.otp.sent.isEmpty())
        }

    @Test
    fun unreadableBodiesAre400() =
        apiTest {
            client.postJson("/v1/auth/start", """{"e164":""").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.postJson("/v1/auth/start", """{}""").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client.postJson("/v1/auth/verify", """{"e164":"+12015550123"}""").assertProblem(HttpStatusCode.BadRequest, "invalid_request")
            client
                .postJson(
                    "/v1/auth/verify",
                    """{"e164":"+12015550123","code":"123456","flavor":"beta"}""",
                ).assertProblem(HttpStatusCode.BadRequest, "invalid_request")
        }

    @Test
    fun unknownRoutesMethodsAndMediaTypesAreProblemJson() =
        apiTest {
            client.get("/v1/nope").assertProblem(HttpStatusCode.NotFound, "not_found")
            val wrongMethod = client.get("/v1/auth/start")
            wrongMethod.assertProblem(HttpStatusCode.MethodNotAllowed, "method_not_allowed")
            assertEquals("POST", wrongMethod.headers[HttpHeaders.Allow])
            client.get("/v1/auth/start/extra").assertProblem(HttpStatusCode.NotFound, "not_found")
            client
                .post("/v1/auth/start") {
                    contentType(ContentType.Text.Plain)
                    setBody("e164=+12015550123")
                }.assertProblem(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type")
        }

    @Test
    fun oversizedBodiesAre413() =
        apiTest {
            client
                .postJson(
                    "/v1/auth/start",
                    """{"e164":"${"1".repeat(MAX_BODY_BYTES.toInt())}"}""",
                ).assertProblem(HttpStatusCode.PayloadTooLarge, "payload_too_large")
        }

    @Test
    fun everyResponseCarriesARequestId() =
        apiTest {
            val generated = requestIdOf(client.get("/health"))
            assertNotNull(generated)
            assertTrue(generated.matches(Regex("[0-9a-f-]{36}")), generated)

            assertEquals("client-id_42", requestIdOf(client.get("/health") { header(HttpHeaders.XRequestId, "client-id_42") }))
            val replaced = requestIdOf(client.get("/health") { header(HttpHeaders.XRequestId, "bad id\"with<chars>") })
            assertTrue(replaced!!.matches(Regex("[0-9a-f-]{36}")), replaced)

            assertNotNull(requestIdOf(client.get("/v1/nope")))
        }
}
