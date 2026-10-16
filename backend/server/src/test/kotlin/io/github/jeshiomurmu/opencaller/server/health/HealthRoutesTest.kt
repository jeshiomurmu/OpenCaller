package io.github.jeshiomurmu.opencaller.server.health

import io.github.jeshiomurmu.opencaller.server.module
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `health returns ok with the configured version`() =
        testApplication {
            application { module(version = "abc1234") }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("""{"status":"ok","version":"abc1234"}""", response.bodyAsText())
        }

    @Test
    fun `unknown route returns 404`() =
        testApplication {
            application { module(version = "dev") }

            assertEquals(HttpStatusCode.NotFound, client.get("/nope").status)
        }
}
