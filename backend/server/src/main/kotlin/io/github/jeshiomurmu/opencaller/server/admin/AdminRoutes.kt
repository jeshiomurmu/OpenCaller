package io.github.jeshiomurmu.opencaller.server.admin

import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.respondProblem
import io.github.jeshiomurmu.opencaller.server.jobs.JobOutcome
import io.github.jeshiomurmu.opencaller.server.jobs.JobRunner
import io.github.jeshiomurmu.opencaller.server.jobs.LeaderJob
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.security.MessageDigest

/**
 * Admin endpoints on the internal port (docs/rules.md §7: separate port, behind auth; the port is not published by
 * compose): `POST /admin/jobs/{name}` runs a job now (`scoring`, `lists`) and returns its result, or 409 if another
 * run holds the job's lock. Requires `Authorization: Bearer <ADMIN_TOKEN>`; not installed when no token is set.
 */
fun Application.adminModule(
    token: String,
    runner: JobRunner,
    jobs: List<LeaderJob>,
) {
    val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
    routing {
        post("/admin/jobs/{name}") {
            val presented =
                call.request.headers[HttpHeaders.Authorization]
                    .orEmpty()
                    .toByteArray(Charsets.UTF_8)
            if (!MessageDigest.isEqual(presented, expected)) {
                call.respondProblem(ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "A valid admin token is required"))
                return@post
            }
            val job = jobs.firstOrNull { it.name == call.parameters["name"] }
            if (job == null) {
                call.respondProblem(
                    ApiProblem(HttpStatusCode.NotFound, "not_found", "No such job", "Jobs: ${jobs.joinToString { it.name }}."),
                )
                return@post
            }
            when (val outcome = runner.runIfLeader(job)) {
                is JobOutcome.Ran -> {
                    val body =
                        buildJsonObject {
                            put("job", JsonPrimitive(job.name))
                            put("result", outcome.result as? JsonElement ?: JsonPrimitive(outcome.result.toString()))
                        }
                    call.respondText(ApiJson.encodeToString(JsonElement.serializer(), body), ContentType.Application.Json)
                }

                JobOutcome.Busy -> {
                    call.respondProblem(ApiProblem(HttpStatusCode.Conflict, "job_running", "The job is already running"))
                }
            }
        }
    }
}
