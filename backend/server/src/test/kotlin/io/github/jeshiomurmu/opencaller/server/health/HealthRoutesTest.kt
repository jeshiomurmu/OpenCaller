package io.github.jeshiomurmu.opencaller.server.health

import io.github.jeshiomurmu.opencaller.server.http.metricsModule
import io.github.jeshiomurmu.opencaller.server.testing.apiTest
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `/health` on the API port and `/metrics` on the internal port. */
class HealthRoutesTest {
    @Test
    fun healthReturnsOkWithTheConfiguredVersion() =
        apiTest {
            val response = client.get("/health")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
            assertEquals("""{"status":"ok","version":"test"}""", response.bodyAsText())
        }

    @Test
    fun metricsAreNotOnTheApiPort() =
        apiTest {
            client.get("/metrics").assertProblem(HttpStatusCode.NotFound, "not_found")
        }

    @Test
    fun metricsPortServesPrometheusTextWithRequestTimings() =
        apiTest { server ->
            client.get("/health")
            client.get("/v1/nope")
            testApplication {
                application { metricsModule(server.deps.meterRegistry) }
                val metrics = client.get("/metrics")
                assertEquals(HttpStatusCode.OK, metrics.status)
                val text = metrics.bodyAsText()
                assertTrue(text.contains("ktor_http_server_requests_seconds_count"), text.take(500))
                assertTrue(text.contains("""route="/health""""), "route label missing")
                assertTrue(text.contains("ktor_http_server_requests_seconds_bucket"), "histogram buckets missing")
            }
        }

    @Test
    fun everyRequestIsCountedOnceAndNoneStaysActive() =
        apiTest { server ->
            client.get("/health")
            client.get("/v1/nope")
            client.get("/v1/auth/start")
            client.postJson("/v1/auth/start", """{"e164":"1"}""")
            val registry = server.deps.meterRegistry
            assertEquals(0.0, registry.get("ktor.http.server.requests.active").gauge().value(), "active requests")
            val counts = registry.get("ktor.http.server.requests").timers().associate { it.id.getTag("status") to it.count() }
            assertEquals(mapOf<String?, Long>("200" to 1L, "404" to 1L, "405" to 1L, "400" to 1L), counts)
        }
}
