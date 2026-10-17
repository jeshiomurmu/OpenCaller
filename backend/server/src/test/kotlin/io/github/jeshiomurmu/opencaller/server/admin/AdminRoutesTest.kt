package io.github.jeshiomurmu.opencaller.server.admin

import io.github.jeshiomurmu.opencaller.server.testing.TEST_ADMIN_TOKEN
import io.github.jeshiomurmu.opencaller.server.testing.TestServer
import io.github.jeshiomurmu.opencaller.server.testing.assertProblem
import io.github.jeshiomurmu.opencaller.server.testing.insertReports
import io.github.jeshiomurmu.opencaller.server.testing.postJson
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** `POST /admin/jobs/{name}` on the internal port. */
class AdminRoutesTest {
    @Test
    fun jobsRunOnDemandWithTheAdminToken() {
        val server = TestServer()
        runBlocking {
            server.reset()
            insertReports("+8801712345678", 20, trust = 1.0f, at = server.clock.now)
        }
        testApplication {
            application { adminModule(TEST_ADMIN_TOKEN, server.deps.jobRunner, server.deps.jobs) }
            val auth = mapOf("Authorization" to "Bearer $TEST_ADMIN_TOKEN")
            val scoring = client.postJson("/admin/jobs/scoring", "{}", auth)
            assertEquals(HttpStatusCode.OK, scoring.status, scoring.bodyAsText())
            val scored = Json.parseToJsonElement(scoring.bodyAsText()).jsonObject
            assertEquals("scoring", scored["job"]!!.jsonPrimitive.content)
            assertEquals(1, scored["result"]!!.jsonObject["rescored"]!!.jsonPrimitive.int)

            val lists = Json.parseToJsonElement(client.postJson("/admin/jobs/lists", "{}", auth).bodyAsText()).jsonObject
            assertEquals(
                "BD",
                lists["result"]!!
                    .jsonObject["built"]!!
                    .jsonArray
                    .single()
                    .jsonObject["countryIso"]!!
                    .jsonPrimitive.content,
            )

            client.postJson("/admin/jobs/scoring", "{}").assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            client
                .postJson(
                    "/admin/jobs/scoring",
                    "{}",
                    mapOf("Authorization" to "Bearer wrong"),
                ).assertProblem(HttpStatusCode.Unauthorized, "unauthorized")
            client.postJson("/admin/jobs/nope", "{}", auth).assertProblem(HttpStatusCode.NotFound, "not_found")
        }
    }
}
