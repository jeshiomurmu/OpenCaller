package io.github.jeshiomurmu.opencaller.server.profile

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Profile and social endpoints (docs/architecture.md §8), all with an access token and never cached. */
fun Route.profileRoutes(
    profiles: ProfileService,
    visitors: VisitorService,
    contacts: ContactRequestService,
) {
    authenticate(ACCESS_AUTH) {
        get("/v1/me") { call.noStore().respond(profiles.me(call.userId())) }
        put("/v1/me") { call.noStore().respond(profiles.update(call.userId(), call.receive<UpdateMeRequest>())) }
        delete("/v1/me") {
            profiles.delete(call.userId())
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/me/avatar") { call.noStore().respond(profiles.avatarUpload(call.userId())) }
        get("/v1/me/viewers") { call.noStore().respond(visitors.visitors(call.userId(), VisitKind.VIEW)) }
        get("/v1/me/searchers") { call.noStore().respond(visitors.visitors(call.userId(), VisitKind.SEARCH)) }
        get("/v1/profiles/{e164}") { call.noStore().respond(visitors.profile(call.userId(), call.parameters["e164"].orEmpty())) }
        contactRoutes(contacts)
    }
}

private fun Route.contactRoutes(contacts: ContactRequestService) {
    post("/v1/contact-requests") {
        val body = call.receive<ContactRequestBody>()
        call.respond(HttpStatusCode.Accepted, contacts.request(call.userId(), body.e164))
    }
    get("/v1/contact-requests") { call.noStore().respond(contacts.incoming(call.userId())) }
    post("/v1/contact-requests/{id}/approve") { call.respond(contacts.decide(call.userId(), call.requestId(), approve = true)) }
    post("/v1/contact-requests/{id}/deny") { call.respond(contacts.decide(call.userId(), call.requestId(), approve = false)) }
}

private fun ApplicationCall.noStore(): ApplicationCall = apply { response.header(HttpHeaders.CacheControl, "no-store") }

private fun ApplicationCall.requestId(): Long =
    parameters["id"]?.toLongOrNull() ?: throw ApiProblem(HttpStatusCode.NotFound, "not_found", "No such pending contact request")
