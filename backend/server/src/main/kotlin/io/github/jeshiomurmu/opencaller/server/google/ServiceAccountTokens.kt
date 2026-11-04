package io.github.jeshiomurmu.opencaller.server.google

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.jeshiomurmu.opencaller.server.config.ServiceAccountKey
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Instant
import java.util.Date
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** A Google API answered with an error, or couldn't be reached. [status] is the HTTP status if there was one. */
open class GoogleApiException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * OAuth access tokens for one Google [scope], from a service account's key with the JWT bearer grant (RFC 7523) as
 * Google's `ServiceAccountCredentials` does it: an RS256 JWT with `kid` = the key ID, `iss` = the account's e-mail,
 * `aud` = its `token_uri`, `scope`, `iat` and `exp` (one hour), posted as
 * `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=…`. Tokens are cached until a minute before
 * they expire. Used by the Play Developer API (billing) and FCM (push).
 */
class ServiceAccountTokens(
    private val key: ServiceAccountKey,
    private val scope: String,
    private val clock: Clock,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT.toJavaDuration()).build(),
) {
    private val algorithm = Algorithm.RSA256(null, key.privateKey)
    private val lock = Mutex()
    private var cached: CachedToken? = null

    private class CachedToken(
        val value: String,
        val expiresAt: Instant,
    )

    /** A valid access token; [refresh] fetches a new one even if the cached one looks valid (after a 401). */
    suspend fun token(refresh: Boolean = false): String =
        lock.withLock {
            val now = clock.instant()
            cached?.takeIf { !refresh && it.expiresAt.isAfter(now.plusSeconds(MARGIN_SECONDS)) }?.let { return it.value }
            val fresh = request(now)
            cached = fresh
            fresh.value
        }

    private suspend fun request(now: Instant): CachedToken {
        val assertion =
            JWT
                .create()
                .withKeyId(key.privateKeyId)
                .withIssuer(key.clientEmail)
                .withAudience(key.tokenUri)
                .withClaim("scope", scope)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(ASSERTION_LIFETIME_SECONDS)))
                .sign(algorithm)
        val form = "grant_type=${URLEncoder.encode(GRANT_TYPE, Charsets.UTF_8)}&assertion=${URLEncoder.encode(assertion, Charsets.UTF_8)}"
        val request =
            HttpRequest
                .newBuilder(URI.create(key.tokenUri))
                .timeout(TIMEOUT.toJavaDuration())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response =
            try {
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: IOException) {
                throw GoogleApiException("Google's token endpoint could not be reached", cause = e)
            }
        if (response.statusCode() !in OK_RANGE) {
            log.warn("Google token request failed: HTTP {}", response.statusCode())
            throw GoogleApiException("Google's token endpoint returned HTTP ${response.statusCode()}", response.statusCode())
        }
        val body =
            try {
                json.decodeFromString(TokenBody.serializer(), response.body())
            } catch (e: SerializationException) {
                throw GoogleApiException("Google's token response is unreadable", response.statusCode(), e)
            }
        return CachedToken(body.accessToken, now.plusSeconds(body.expiresIn))
    }

    @Serializable
    private data class TokenBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = DEFAULT_TOKEN_SECONDS,
    )

    private companion object {
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val ASSERTION_LIFETIME_SECONDS = 3600L
        const val DEFAULT_TOKEN_SECONDS = 3600L
        const val MARGIN_SECONDS = 60L
        val OK_RANGE = 200..299
        val TIMEOUT = 10.seconds
        val json = Json { ignoreUnknownKeys = true }
        val log = LoggerFactory.getLogger(ServiceAccountTokens::class.java)
    }
}
