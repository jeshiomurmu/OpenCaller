package io.github.jeshiomurmu.opencaller.server.unlist

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * Unlisting: the JSON API (`POST /v1/unlist/start`, `/v1/unlist/verify`) and the same flow as plain HTML forms at
 * `/unlist` for people without the app. Errors show on the form instead of as problem+json.
 */
fun Route.unlistRoutes(unlist: UnlistService) {
    post("/v1/unlist/start") {
        val request = call.receive<UnlistStartRequest>()
        call.respond(HttpStatusCode.Accepted, unlist.start(request.e164, call.request.origin.remoteAddress))
    }
    post("/v1/unlist/verify") {
        val request = call.receive<UnlistVerifyRequest>()
        call.respond(HttpStatusCode.Accepted, unlist.verify(request.e164, request.code))
    }
    get("/unlist") {
        call.respondPage(UnlistPages.numberForm())
    }
    post("/unlist") {
        val number = call.receiveParameters()["e164"].orEmpty().take(MAX_FIELD)
        try {
            val started = unlist.start(number, call.request.origin.remoteAddress)
            call.respondPage(UnlistPages.codeForm(number, started.expiresInSeconds / SECONDS_PER_MINUTE))
        } catch (problem: ApiProblem) {
            problem.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respondPage(UnlistPages.numberForm(problem.forPeople()), problem.status)
        }
    }
    post("/unlist/verify") {
        val form = call.receiveParameters()
        val number = form["e164"].orEmpty().take(MAX_FIELD)
        try {
            val done = unlist.verify(number, form["code"].orEmpty().trim().take(MAX_FIELD))
            call.respondPage(UnlistPages.done(number, done.processedWithinMinutes))
        } catch (problem: ApiProblem) {
            val page =
                if (problem.code == "invalid_phone_number") {
                    UnlistPages.numberForm(problem.forPeople())
                } else {
                    UnlistPages.codeForm(number, unlist.codeValidMinutes, problem.forPeople())
                }
            call.respondPage(page, problem.status)
        }
    }
}

private fun ApiProblem.forPeople() = listOfNotNull(title, detail).joinToString(". ")

private suspend fun ApplicationCall.respondPage(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    // No framing (clickjacking), no scripts, forms post only back to this server.
    response.header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
    response.header("X-Frame-Options", "DENY")
    response.header("Referrer-Policy", "no-referrer")
    response.header("Cache-Control", "no-store")
    respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

private const val MAX_FIELD = 64
private const val SECONDS_PER_MINUTE = 60
