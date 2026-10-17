package io.github.jeshiomurmu.opencaller.server.lookup

import com.auth0.jwt.interfaces.JWTVerifier
import io.github.jeshiomurmu.opencaller.server.ACCESS_AUTH
import io.github.jeshiomurmu.opencaller.server.auth.optionalUserId
import io.github.jeshiomurmu.opencaller.server.auth.userId
import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.respondCacheable
import io.github.jeshiomurmu.opencaller.server.search.SearchService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/lookup/buckets/{cc}/{bucketId}` (token optional; ETag, cacheable 24 h) and `GET /v1/numbers/{e164}`
 * (access token, never cached).
 */
fun Route.lookupRoutes(
    lookup: LookupService,
    search: SearchService,
    verifier: JWTVerifier,
) {
    get("/v1/lookup/buckets/{cc}/{bucketId}") {
        val authenticated = call.optionalUserId(verifier) != null
        val cc = call.parameters["cc"]?.toIntOrNull() ?: badParameter("cc must be a country calling code, e.g. 880.")
        val bucket = call.parameters["bucketId"]?.toIntOrNull() ?: badParameter("bucketId must be a number.")
        val bits =
            call.request.queryParameters["bits"]?.let { it.toIntOrNull() ?: badParameter("bits must be a number.") }
                ?: BucketHasher.DEFAULT_BITS
        val body = ApiJson.encodeToString(BucketResponse.serializer(), lookup.bucket(cc, bucket, bits, authenticated))
        // With a token the response may hold registered-only names, so only the app may cache it, not shared caches.
        val scope = if (authenticated) "private" else "public"
        call.respondCacheable(
            body.toByteArray(Charsets.UTF_8),
            ContentType.Application.Json,
            cacheControl = "$scope, max-age=${LookupService.MAX_AGE_SECONDS}",
            vary = HttpHeaders.Authorization,
        )
    }
    authenticate(ACCESS_AUTH) {
        get("/v1/numbers/{e164}") {
            val result = search.search(call.userId(), call.parameters["e164"].orEmpty())
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(result)
        }
    }
}

private fun badParameter(detail: String): Nothing = throw ApiProblem(HttpStatusCode.BadRequest, "invalid_bucket", "Invalid bucket", detail)
