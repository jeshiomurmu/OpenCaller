package io.github.jeshiomurmu.opencaller.server.business

import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.http.respondCacheable
import io.ktor.http.ContentType
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
import java.util.UUID

/**
 * Business endpoints (docs/architecture.md §8): claims need an access token and are never cached; a verified
 * business's profile, its logo and the per-country directory are public and cacheable.
 */
fun Route.businessRoutes(
    claims: ClaimService,
    directory: BusinessDirectory,
) {
    authenticate(ACCESS_AUTH) { claimRoutes(claims) }
    get("/v1/business/{id}") {
        val body = ApiJson.encodeToString(BusinessProfileResponse.serializer(), directory.profile(call.parameters["id"].orEmpty()))
        call.respondCacheable(body.toByteArray(Charsets.UTF_8), ContentType.Application.Json, PROFILE_CACHE)
    }
    get("/v1/business/{id}/logo") {
        val (bytes, type) = directory.logo(call.parameters["id"].orEmpty())
        call.respondCacheable(bytes, ContentType.parse(type), LOGO_CACHE)
    }
    get("/v1/businesses/{countryIso}") {
        val response = directory.directory(call.parameters["countryIso"].orEmpty(), call.request.queryParameters["since"])
        val body = ApiJson.encodeToString(DirectoryResponse.serializer(), response)
        call.respondCacheable(body.toByteArray(Charsets.UTF_8), ContentType.Application.Json, DIRECTORY_CACHE)
    }
}

private fun Route.claimRoutes(claims: ClaimService) {
    post("/v1/business/claims") {
        call.noStore().respond(HttpStatusCode.Created, claims.start(call.userId(), call.receive<StartClaimRequest>()))
    }
    get("/v1/business/claims") { call.noStore().respond(claims.list(call.userId())) }
    post("/v1/business/claims/{id}/code") {
        call.noStore().respond(claims.verify(call.userId(), call.claimId(), call.receive<ClaimCodeRequest>().code))
    }
    post("/v1/business/claims/{id}/resend") {
        call.noStore().respond(claims.resend(call.userId(), call.claimId(), call.receive<ResendCodeRequest>().method))
    }
    post("/v1/business/claims/{id}/uploads") {
        call.noStore().respond(claims.upload(call.userId(), call.claimId(), call.receive<ClaimUploadRequest>()))
    }
    post("/v1/business/claims/{id}/submit") {
        call.noStore().respond(claims.submit(call.userId(), call.claimId(), call.receive<SubmitClaimRequest>()))
    }
    delete("/v1/business/claims/{id}") {
        claims.withdraw(call.userId(), call.claimId())
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun ApplicationCall.noStore(): ApplicationCall = apply { response.header(HttpHeaders.CacheControl, "no-store") }

private fun ApplicationCall.claimId(): UUID =
    parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw claimNotFound()

private const val PROFILE_CACHE = "public, max-age=3600"
private const val LOGO_CACHE = "public, max-age=86400"
private const val DIRECTORY_CACHE = "public, max-age=3600"
