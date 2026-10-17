package io.github.jeshiomurmu.opencaller.server.lists

import io.github.jeshiomurmu.opencaller.server.http.ApiJson
import io.github.jeshiomurmu.opencaller.server.http.respondCacheable
import io.ktor.http.ContentType
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** `GET /v1/lists/{countryIso}/manifest` and the immutable list files it points to. */
fun Route.listRoutes(lists: ListService) {
    get("/v1/lists/{countryIso}/manifest") {
        val body = ApiJson.encodeToString(ListManifest.serializer(), lists.manifest(call.parameters["countryIso"].orEmpty()))
        call.respondCacheable(
            body.toByteArray(Charsets.UTF_8),
            ContentType.Application.Json,
            cacheControl = "public, max-age=$MANIFEST_MAX_AGE",
        )
    }
    get("/v1/lists/{countryIso}/files/{version}/{kind}") {
        val file =
            lists.file(
                call.parameters["countryIso"].orEmpty(),
                call.parameters["version"].orEmpty(),
                call.parameters["kind"].orEmpty(),
            )
        call.respondCacheable(
            file.bytes,
            ContentType.Application.OctetStream,
            cacheControl = "public, max-age=$FILE_MAX_AGE, immutable",
            etag = "\"${file.sha256}\"",
        )
    }
}

// A new build shows up in the manifest within 5 minutes; published files never change.
private const val MANIFEST_MAX_AGE = 300
private const val FILE_MAX_AGE = 31_536_000
