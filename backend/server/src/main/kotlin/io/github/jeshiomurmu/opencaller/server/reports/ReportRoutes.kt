package io.github.jeshiomurmu.opencaller.server.reports

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/** `POST /v1/reports` (access token). */
fun Route.reportRoutes(reports: ReportService) {
    authenticate(ACCESS_AUTH) {
        post("/v1/reports") {
            call.respond(HttpStatusCode.Accepted, reports.submit(call.userId(), call.receive<ReportBatch>()))
        }
    }
}
