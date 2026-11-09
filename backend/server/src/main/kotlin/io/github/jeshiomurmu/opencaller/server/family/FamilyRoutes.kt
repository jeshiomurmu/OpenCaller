package io.github.jeshiomurmu.opencaller.server.family

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
import java.util.UUID

/**
 * Family protection (docs/prd.md F-PR-14, ADR 0008). Every route needs an access token; a membership is only ever
 * created by its own member ([FamilyService.create], [FamilyService.join]).
 */
fun Route.familyRoutes(
    family: FamilyService,
    alerts: FamilyAlertService,
) {
    authenticate(ACCESS_AUTH) {
        post("/v1/family") {
            call.noStore().respond(HttpStatusCode.Created, family.create(call.userId(), call.receive<CreateFamilyRequest>()))
        }
        get("/v1/family") { call.noStore().respond(family.view(call.userId())) }
        post("/v1/family/invites") { call.noStore().respond(HttpStatusCode.Created, family.invite(call.userId())) }
        get("/v1/family/invites/{code}") {
            call.noStore().respond(family.preview(call.userId(), call.parameters["code"].orEmpty().take(MAX_CODE_PARAMETER)))
        }
        post("/v1/family/join") { call.noStore().respond(family.join(call.userId(), call.receive<JoinFamilyRequest>())) }
        put("/v1/family/me") { call.noStore().respond(family.updateMe(call.userId(), call.receive<FamilySharingRequest>())) }
        delete("/v1/family/me") {
            family.leave(call.userId())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/v1/family/members/{id}") {
            family.remove(call.userId(), memberId(call.parameters["id"]))
            call.respond(HttpStatusCode.NoContent)
        }
        post("/v1/family/alerts") {
            call.noStore().respond(HttpStatusCode.Created, alerts.alert(call.userId(), call.receive<FamilyAlertRequest>()))
        }
        put("/v1/family/counts/{day}") {
            alerts.counts(call.userId(), call.parameters["day"].orEmpty().take(MAX_DAY_PARAMETER), call.receive<FamilyCountsRequest>())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private const val MAX_CODE_PARAMETER = 32
private const val MAX_DAY_PARAMETER = 16
private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun memberId(value: String?): UUID =
    value?.takeIf(UUID_PATTERN::matches)?.let(UUID::fromString)
        ?: throw ApiProblem(HttpStatusCode.NotFound, "member_not_found", "No such member in your group")

private fun ApplicationCall.noStore(): ApplicationCall = apply { response.header(HttpHeaders.CacheControl, "no-store") }
