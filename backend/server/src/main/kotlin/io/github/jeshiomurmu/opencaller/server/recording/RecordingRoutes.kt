package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.respondProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.security.MessageDigest
import java.util.UUID

/**
 * The recording line (docs/prd.md F-UT-03, ADR 0009): the app's routes under `/v1` (access token) and the agents'
 * under `/agent/v1` (bearer `RECORDING_AGENT_TOKEN`, compared in constant time).
 */
fun Route.recordingRoutes(services: RecordingRouteServices) {
    authenticate(ACCESS_AUTH) {
        post("/v1/lines/recording") {
            call.noStore().respond(HttpStatusCode.Created, services.lines.allocate(call.userId()))
        }
        get("/v1/lines/recording/{id}") {
            call.noStore().respond(services.lines.session(call.userId(), idOf(call.parameters["id"], ::sessionNotFound)))
        }
        get("/v1/recordings") { call.noStore().respond(services.recordings.list(call.userId())) }
        get("/v1/recordings/settings") { call.noStore().respond(services.recordings.settings(call.userId())) }
        put("/v1/recordings/settings") {
            call.noStore().respond(services.recordings.updateSettings(call.userId(), call.receive<RecordingSettings>()))
        }
        get("/v1/recordings/{id}/download") {
            call.noStore().respond(services.recordings.download(call.userId(), recordingId()))
        }
        post("/v1/recordings/{id}/downloaded") {
            services.recordings.downloaded(call.userId(), recordingId())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/v1/recordings/{id}") {
            services.recordings.delete(call.userId(), recordingId())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    services.agentToken?.let { agentRoutes(services, it) }
}

private fun Route.agentRoutes(
    services: RecordingRouteServices,
    token: String,
) {
    val expected = "Bearer $token".toByteArray(Charsets.UTF_8)
    val agent: suspend RoutingContext.(suspend RoutingContext.() -> Unit) -> Unit = { handler ->
        val given =
            call.request.headers[HttpHeaders.Authorization]
                .orEmpty()
                .toByteArray(Charsets.UTF_8)
        if (MessageDigest.isEqual(given, expected)) {
            handler()
        } else {
            call.respondProblem(ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "A valid agent token is required"))
        }
    }
    post("/agent/v1/recording/claim") { agent { call.noStore().respond(services.lines.claim(call.receive<ClaimRequest>())) } }
    post("/agent/v1/recording/sessions/{id}/result") {
        agent {
            services.agents.result(idOf(call.parameters["id"], ::sessionNotFound), call.receive<RecordingResultRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    post("/agent/v1/transcripts/next") {
        agent {
            val job = services.agents.nextTranscript()
            if (job == null) call.respond(HttpStatusCode.NoContent) else call.noStore().respond(job)
        }
    }
    put("/agent/v1/transcripts/{id}") {
        agent {
            services.agents.transcript(recordingId(), call.receive<TranscriptResult>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun idOf(
    value: String?,
    missing: () -> ApiProblem,
): UUID = value?.takeIf(UUID_PATTERN::matches)?.let(UUID::fromString) ?: throw missing()

private fun RoutingContext.recordingId(): UUID = idOf(call.parameters["id"], ::recordingNotFound)

private fun ApplicationCall.noStore(): ApplicationCall = apply { response.header(HttpHeaders.CacheControl, "no-store") }
