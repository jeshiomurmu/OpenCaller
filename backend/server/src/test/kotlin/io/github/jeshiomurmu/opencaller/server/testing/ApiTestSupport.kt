package io.github.jeshiomurmu.opencaller.server.testing

import io.github.jeshiomurmu.opencaller.server.auth.TokenResponse
import io.github.jeshiomurmu.opencaller.server.http.ProblemDetails
import io.github.jeshiomurmu.opencaller.server.http.ProblemJson
import io.github.jeshiomurmu.opencaller.server.module
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals

val testJson = Json { ignoreUnknownKeys = true }

/** Resets [server]'s state and runs [block] against the real module. */
fun apiTest(
    server: TestServer = TestServer(),
    block: suspend ApplicationTestBuilder.(TestServer) -> Unit,
) {
    runBlocking { server.reset() }
    testApplication {
        application { module(server.deps) }
        block(server)
    }
}

/** POSTs [json] to [path] with extra [headers]. */
suspend fun HttpClient.postJson(
    path: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        headers.forEach { (name, value) -> header(name, value) }
        setBody(json)
    }

/** Asserts a problem+json response with [status] and [code] and returns it. */
suspend fun HttpResponse.assertProblem(
    status: HttpStatusCode,
    code: String,
): ProblemDetails {
    assertEquals(status, this.status, bodyAsText())
    assertEquals(ProblemJson, contentType()?.withoutParameters())
    val problem = testJson.decodeFromString(ProblemDetails.serializer(), bodyAsText())
    assertEquals(code, problem.code)
    assertEquals(status.value, problem.status)
    assertEquals("urn:opencaller:problem:$code", problem.type)
    return problem
}

/** Signs [number] in through start + verify and returns the tokens. */
suspend fun ApplicationTestBuilder.signIn(
    server: TestServer,
    number: String,
): TokenResponse {
    assertEquals(HttpStatusCode.Accepted, client.postJson("/v1/auth/start", """{"e164":"$number"}""").status)
    val response = client.postJson("/v1/auth/verify", """{"e164":"$number","code":"${server.otp.lastCodeFor(number)}","flavor":"foss"}""")
    assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    return testJson.decodeFromString(TokenResponse.serializer(), response.bodyAsText())
}

/** The `X-Request-Id` of [response]. */
fun requestIdOf(response: HttpResponse): String? = response.headers[HttpHeaders.XRequestId]
