package io.github.jeshiomurmu.opencaller.server.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** `POST /v1/auth/start`, `/verify` and `/refresh` (docs/architecture.md §8; openapi.yaml). */
fun Route.authRoutes(auth: AuthService) {
    route("/v1/auth") {
        post("/start") {
            val response = auth.start(call.receive<StartRequest>(), clientIp = call.request.origin.remoteAddress)
            call.respond(HttpStatusCode.Accepted, response)
        }
        post("/verify") {
            call.respond(auth.verify(call.receive<VerifyRequest>()))
        }
        post("/refresh") {
            call.respond(auth.refresh(call.receive<RefreshRequest>()))
        }
    }
}
