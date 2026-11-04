package io.github.jeshiomurmu.opencaller.server.billing

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** `GET /v1/server` (public), `GET /v1/entitlements` and `POST /v1/billing/verify` (access token), never cached. */
fun Route.billingRoutes(
    billing: BillingService,
    features: FeatureInfo = FeatureInfo(),
) {
    get("/v1/server") { call.noStore().respond(billing.serverInfo(features)) }
    authenticate(ACCESS_AUTH) {
        get("/v1/entitlements") { call.noStore().respond(billing.entitlements(call.userId())) }
        post("/v1/billing/verify") {
            val body = call.receive<VerifyPurchaseRequest>()
            call.noStore().respond(billing.verify(call.userId(), body))
        }
    }
}

private fun ApplicationCall.noStore(): ApplicationCall = apply { response.header(HttpHeaders.CacheControl, "no-store") }
