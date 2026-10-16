package io.github.jeshiomurmu.opencaller.server.health

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/** Registers `GET /health`, used by Docker healthchecks and uptime monitoring. */
fun Application.configureHealthRoutes(version: String) {
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "ok", version = version))
        }
    }
}
