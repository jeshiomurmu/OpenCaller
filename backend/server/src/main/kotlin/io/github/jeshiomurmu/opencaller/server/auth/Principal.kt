package io.github.jeshiomurmu.opencaller.server.auth

import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.JWTVerifier
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import java.util.UUID

/** The signed-in user of an `authenticate(ACCESS_AUTH)` route (the token's `sub`). */
fun ApplicationCall.userId(): UUID = UUID.fromString(checkNotNull(principal<JWTPrincipal>()).payload.subject)

/**
 * For endpoints where the access token is optional (docs/architecture.md §8): the user if a valid bearer token
 * was sent, `null` if none was sent, and 401 `unauthorized` if one was sent but is invalid or expired (so an app
 * with a stale token learns to refresh instead of silently getting less data).
 */
fun ApplicationCall.optionalUserId(verifier: JWTVerifier): UUID? {
    val header = request.headers[HttpHeaders.Authorization] ?: return null
    val token = header.takeIf { it.startsWith(BEARER, ignoreCase = true) }?.substring(BEARER.length)?.trim() ?: unauthorized()
    val payload =
        try {
            verifier.verify(token)
        } catch (e: JWTVerificationException) {
            unauthorized(e)
        }
    val userId = payload.subject?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    return userId?.takeIf { payload.getClaim(TokenService.DEVICE_CLAIM).asString() != null } ?: unauthorized()
}

private const val BEARER = "Bearer "

private fun unauthorized(cause: Throwable? = null): Nothing =
    throw ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "A valid access token is required", cause = cause)
