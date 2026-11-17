package io.github.jeshiomurmu.opencaller.server.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The minimum app version (426 for outdated apps) and the forwarded-header trust behind Caddy (Phase 15). */
class ClientVersionTest {
    private fun Application.probe() {
        routing {
            get("/v1/probe") { call.respondText(call.request.origin.remoteAddress) }
            get("/health") { call.respondText("ok") }
        }
    }

    @Test
    fun anOlderAppGetsUpgradeRequiredOnTheApi() =
        testApplication {
            application {
                installMinimumAppVersion(1010099)
                probe()
            }
            val response = client.get("/v1/probe") { header(APP_VERSION_HEADER, "1000099") }
            assertEquals(HttpStatusCode.UpgradeRequired, response.status)
            assertTrue(response.bodyAsText().contains("\"code\":\"upgrade_required\""), response.bodyAsText())
        }

    @Test
    fun theMinimumVersionAndNewerAreServed() =
        testApplication {
            application {
                installMinimumAppVersion(1010099)
                probe()
            }
            for (version in listOf("1010099", "1020099")) {
                val response = client.get("/v1/probe") { header(APP_VERSION_HEADER, version) }
                assertEquals(HttpStatusCode.OK, response.status, version)
            }
        }

    @Test
    fun requestsWithoutTheHeaderAndOutsideTheApiAreServed() =
        testApplication {
            application {
                installMinimumAppVersion(1010099)
                probe()
            }
            assertEquals(HttpStatusCode.OK, client.get("/v1/probe").status)
            assertEquals(HttpStatusCode.OK, client.get("/v1/probe") { header(APP_VERSION_HEADER, "not-a-number") }.status)
            assertEquals(HttpStatusCode.OK, client.get("/health") { header(APP_VERSION_HEADER, "1") }.status)
        }

    @Test
    fun zeroTurnsTheCheckOff() =
        testApplication {
            application {
                installMinimumAppVersion(0)
                probe()
            }
            assertEquals(HttpStatusCode.OK, client.get("/v1/probe") { header(APP_VERSION_HEADER, "1") }.status)
        }

    @Test
    fun behindTheProxyTheClientIsTheLastForwardedAddress() =
        testApplication {
            application {
                installForwardedHeaders(trusted = true)
                probe()
            }
            // A client's own X-Forwarded-For comes first; Caddy appends (or replaces it with) the real address.
            val response = client.get("/v1/probe") { header("X-Forwarded-For", "203.0.113.9, 198.51.100.7") }
            assertEquals("198.51.100.7", response.bodyAsText())
        }

    @Test
    fun withoutTrustTheForwardedHeaderIsIgnored() =
        testApplication {
            application {
                installForwardedHeaders(trusted = false)
                probe()
            }
            val response = client.get("/v1/probe") { header("X-Forwarded-For", "198.51.100.7") }
            assertTrue(response.bodyAsText() != "198.51.100.7", response.bodyAsText())
        }
}
