package io.github.jeshiomurmu.opencaller.server.sync

import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.http.respondCacheable
import io.ktor.http.ContentType
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /v1/sender-ids/{countryIso}?since=` and `GET /v1/otp-sources?since=` (no auth; cacheable 1 h). */
fun Route.syncRoutes(sync: SyncService) {
    get("/v1/sender-ids/{countryIso}") {
        val response = sync.senderIds(call.parameters["countryIso"].orEmpty(), call.request.queryParameters["since"])
        val body = ApiJson.encodeToString(SenderIdsResponse.serializer(), response)
        call.respondCacheable(body.toByteArray(Charsets.UTF_8), ContentType.Application.Json, CACHE_CONTROL)
    }
    get("/v1/otp-sources") {
        val body = ApiJson.encodeToString(OtpSourcesResponse.serializer(), sync.otpSources(call.request.queryParameters["since"]))
        call.respondCacheable(body.toByteArray(Charsets.UTF_8), ContentType.Application.Json, CACHE_CONTROL)
    }
}

private const val CACHE_CONTROL = "public, max-age=3600"
