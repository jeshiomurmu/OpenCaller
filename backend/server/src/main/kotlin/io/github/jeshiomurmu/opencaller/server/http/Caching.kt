package io.github.jeshiomurmu.opencaller.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import java.security.MessageDigest

/** A strong ETag for [bytes]: the first 128 bits of their SHA-256, quoted. */
fun etagOf(bytes: ByteArray): String =
    "\"" +
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .copyOf(ETAG_BYTES)
            .toHexString() + "\""

/** `true` if the request's `If-None-Match` lists [etag] (or `*`); weak validators match too (RFC 9110 §13.1.2). */
fun ApplicationCall.matchesIfNoneMatch(etag: String): Boolean {
    val header = request.headers[HttpHeaders.IfNoneMatch] ?: return false
    return header.split(',').map { it.trim().removePrefix("W/") }.any { it == "*" || it == etag }
}

/**
 * Responds with [bytes] and their ETag, or `304 Not Modified` (no body) if the client already has them.
 * [cacheControl] and [vary] are sent with both.
 */
suspend fun ApplicationCall.respondCacheable(
    bytes: ByteArray,
    contentType: ContentType,
    cacheControl: String,
    etag: String = etagOf(bytes),
    vary: String? = null,
) {
    response.header(HttpHeaders.ETag, etag)
    response.header(HttpHeaders.CacheControl, cacheControl)
    vary?.let { response.header(HttpHeaders.Vary, it) }
    if (matchesIfNoneMatch(etag)) {
        respond(HttpStatusCode.NotModified)
    } else {
        respondBytes(bytes, contentType)
    }
}

private const val ETAG_BYTES = 16
