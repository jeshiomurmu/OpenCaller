package io.github.jeshiomurmu.opencaller.server.disputes

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** `POST /v1/disputes` (access token). */
fun Route.disputeRoutes(disputes: DisputeService) {
    authenticate(ACCESS_AUTH) {
        post("/v1/disputes") {
            call.respond(HttpStatusCode.Created, disputes.open(call.userId(), call.receive<DisputeRequest>()))
        }
    }
}
