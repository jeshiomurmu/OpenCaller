package io.github.jeshiomurmu.opencaller.server.assistant

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.github.jeshiomurmu.opencaller.server.recording.agentBearer
import io.github.jeshiomurmu.opencaller.server.recording.asAgent
import io.github.jeshiomurmu.opencaller.server.recording.idOf
import io.github.jeshiomurmu.opencaller.server.recording.noStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The call assistant (docs/prd.md F-UT-04, ADR 0010): the app's routes under `/v1` (access token) and the agent's
 * under `/agent/v1/assistant` (bearer `RECORDING_AGENT_TOKEN`).
 */
fun Route.assistantRoutes(services: AssistantRouteServices) {
    authenticate(ACCESS_AUTH) {
        post("/v1/lines/assistant") { call.noStore().respond(HttpStatusCode.Created, services.lines.allocate(call.userId())) }
        get("/v1/lines/assistant") { call.noStore().respond(services.lines.line(call.userId())) }
        delete("/v1/lines/assistant") {
            services.lines.release(call.userId())
            call.respond(HttpStatusCode.NoContent)
        }
        get("/v1/assistant/usage") { call.noStore().respond(services.lines.usage(call.userId())) }
        get("/v1/assistant/calls/{id}") {
            val id = idOf(call.parameters["id"], ::assistantCallNotFound)
            call.noStore().respond(services.calls.view(call.userId(), id) ?: throw assistantCallNotFound())
        }
    }
    val token = services.agentToken ?: return
    val expected = agentBearer(token)
    post("/agent/v1/assistant/calls") {
        asAgent(expected) { call.noStore().respond(services.calls.claim(call.receive<AssistantClaimRequest>())) }
    }
    post("/agent/v1/assistant/calls/{id}/end") {
        asAgent(expected) {
            services.calls.end(idOf(call.parameters["id"], ::assistantCallNotFound), call.receive<AssistantEndRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
