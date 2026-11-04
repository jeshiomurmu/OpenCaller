package io.github.jeshiomurmu.opencaller.server.lists

import com.auth0.jwt.interfaces.JWTVerifier
import io.github.jeshiomurmu.opencaller.server.auth.optionalUserId
import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.respondCacheable
import io.github.jeshiomurmu.opencaller.server.profile.Entitlements
import io.github.jeshiomurmu.opencaller.server.profile.supporterRequired
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/lists/{countryIso}/manifest` and the immutable list files it points to. The extended list (F-PR-03) needs
 * a supporter's access token (or a self-hosted server) and is cached privately; everything else is public.
 */
fun Route.listRoutes(
    lists: ListService,
    verifier: JWTVerifier,
    entitlements: Entitlements,
) {
    get("/v1/lists/{countryIso}/manifest") {
        val body = ApiJson.encodeToString(ListManifest.serializer(), lists.manifest(call.parameters["countryIso"].orEmpty()))
        call.respondCacheable(
            body.toByteArray(Charsets.UTF_8),
            ContentType.Application.Json,
            cacheControl = "public, max-age=$MANIFEST_MAX_AGE",
        )
    }
    get("/v1/lists/{countryIso}/files/{version}/{kind}") {
        val kind = call.parameters["kind"].orEmpty()
        val extended = kind == ListKind.EXTENDED.sqlValue
        if (extended && !entitlements.everyone) {
            val userId =
                call.optionalUserId(verifier)
                    ?: throw ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "A valid access token is required")
            if (!entitlements.isSupporter(userId)) throw supporterRequired()
        }
        val file = lists.file(call.parameters["countryIso"].orEmpty(), call.parameters["version"].orEmpty(), kind)
        call.respondCacheable(
            file.bytes,
            ContentType.Application.OctetStream,
            cacheControl = if (extended) "private, max-age=$FILE_MAX_AGE, immutable" else "public, max-age=$FILE_MAX_AGE, immutable",
            etag = "\"${file.sha256}\"",
        )
    }
}

// A new build shows up in the manifest within 5 minutes; published files never change.
private const val MANIFEST_MAX_AGE = 300
private const val FILE_MAX_AGE = 31_536_000
