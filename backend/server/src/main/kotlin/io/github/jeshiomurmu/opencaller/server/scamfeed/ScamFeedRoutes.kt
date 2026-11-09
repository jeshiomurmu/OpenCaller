package io.github.jeshiomurmu.opencaller.server.scamfeed

import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/scam-feed/{iso}` (docs/prd.md F-PR-15): public, no sign-in; the request names only the country, so a
 * shared cache may keep the answer for an hour.
 */
fun Route.scamFeedRoutes(scamFeed: ScamFeedService) {
    get("/v1/scam-feed/{iso}") {
        val feed =
            scamFeed.feed(
                call.parameters["iso"]
                    .orEmpty()
                    .take(MAX_ISO_PARAMETER)
                    .uppercase(),
            )
        call.response.header(HttpHeaders.CacheControl, "public, max-age=$CACHE_SECONDS")
        call.respond(feed)
    }
}

private const val MAX_ISO_PARAMETER = 8
private const val CACHE_SECONDS = 3600
