package io.github.jeshiomurmu.opencaller.server.billing

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.github.jeshiomurmu.opencaller.server.config.GooglePlayConfig
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
import java.time.format.DateTimeParseException
import java.util.Date
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** One item of a subscription purchase: the product, its base plan, and when the paid period ends. */
data class PlayLineItem(
    val productId: String,
    val basePlanId: String?,
    val expiryTime: Instant?,
    val autoRenewing: Boolean,
)

/**
 * A Play subscription purchase as `purchases.subscriptionsv2.get` reports it, reduced to what OpenCaller uses.
 * [state] is Google's `subscriptionState` (e.g. `SUBSCRIPTION_STATE_ACTIVE`).
 */
data class PlaySubscription(
    val state: String,
    val acknowledged: Boolean,
    val testPurchase: Boolean,
    val linkedPurchaseToken: String?,
    val obfuscatedAccountId: String?,
    val lineItems: List<PlayLineItem>,
)

/** Google answered with an error, or couldn't be reached. [status] is the HTTP status if there was one. */
class PlayApiException(
    message: String,
    val status: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

/** The two Play Developer API calls billing needs. */
interface PlaySubscriptions {
    /** The purchase behind [purchaseToken], or `null` if Google doesn't know the token. */
    suspend fun subscription(purchaseToken: String): PlaySubscription?

    /** Acknowledges the purchase (Play refunds purchases that aren't acknowledged within three days). */
    suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    )
}

/**
 * The Google Play Developer API without Google's client libraries, written against the official discovery document
 * (`androidpublisher` v3, revision 20261102):
 * `GET androidpublisher/v3/applications/{packageName}/purchases/subscriptionsv2/tokens/{token}` and
 * `POST androidpublisher/v3/applications/{packageName}/purchases/subscriptions/{subscriptionId}/tokens/{token}:acknowledge`,
 * both with the scope `https://www.googleapis.com/auth/androidpublisher`. Access tokens come from the service
 * account's key with the JWT bearer grant (RFC 7523), as Google's `ServiceAccountCredentials` does it: an RS256 JWT
 * with `kid` = the key ID, `iss` = the account's e-mail, `aud` = its `token_uri`, `scope`, `iat` and `exp` (one hour),
 * posted as `grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=…`.
 */
class GooglePlayDeveloperApi(
    private val config: GooglePlayConfig,
    private val clock: Clock,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT.toJavaDuration()).build(),
) : PlaySubscriptions {
    private val algorithm = Algorithm.RSA256(null, config.serviceAccount.privateKey)
    private val tokenLock = Mutex()
    private var accessToken: CachedToken? = null

    private class CachedToken(
        val value: String,
        val expiresAt: Instant,
    )

    override suspend fun subscription(purchaseToken: String): PlaySubscription? {
        val uri = URI.create("${apps()}/purchases/subscriptionsv2/tokens/${segment(purchaseToken)}")
        val response = authorized { HttpRequest.newBuilder(uri).GET().header("Accept", "application/json") }
        return when (response.statusCode()) {
            in OK_RANGE -> parseSubscription(response.body())
            NOT_FOUND, GONE -> null
            else -> throw failure("subscriptionsv2.get", response)
        }
    }

    override suspend fun acknowledge(
        productId: String,
        purchaseToken: String,
    ) {
        val uri = URI.create("${apps()}/purchases/subscriptions/${segment(productId)}/tokens/${segment(purchaseToken)}:acknowledge")
        val response =
            authorized {
                HttpRequest
                    .newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
            }
        if (response.statusCode() !in OK_RANGE) throw failure("subscriptions.acknowledge", response)
    }

    private fun apps() = "${config.apiBaseUrl}/androidpublisher/v3/applications/${segment(config.packageName)}"

    // A 401 means the cached access token was revoked or expired early: fetch a new one and try once more.
    private suspend fun authorized(build: () -> HttpRequest.Builder): HttpResponse<String> {
        val first = send(build().header("Authorization", "Bearer ${token(refresh = false)}"))
        if (first.statusCode() != UNAUTHORIZED) return first
        return send(build().header("Authorization", "Bearer ${token(refresh = true)}"))
    }

    private suspend fun send(builder: HttpRequest.Builder): HttpResponse<String> =
        try {
            http.sendAsync(builder.timeout(TIMEOUT.toJavaDuration()).build(), HttpResponse.BodyHandlers.ofString()).await()
        } catch (e: IOException) {
            throw PlayApiException("Google Play could not be reached", cause = e)
        }

    private suspend fun token(refresh: Boolean): String =
        tokenLock.withLock {
            val now = clock.instant()
            accessToken?.takeIf { !refresh && it.expiresAt.isAfter(now.plusSeconds(TOKEN_MARGIN_SECONDS)) }?.let { return it.value }
            val fresh = requestToken(now)
            accessToken = fresh
            fresh.value
        }

    private suspend fun requestToken(now: Instant): CachedToken {
        val key = config.serviceAccount
        val assertion =
            JWT
                .create()
                .withKeyId(key.privateKeyId)
                .withIssuer(key.clientEmail)
                .withAudience(key.tokenUri)
                .withClaim("scope", SCOPE)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plusSeconds(ASSERTION_LIFETIME_SECONDS)))
                .sign(algorithm)
        val form = "grant_type=${URLEncoder.encode(GRANT_TYPE, Charsets.UTF_8)}&assertion=${URLEncoder.encode(assertion, Charsets.UTF_8)}"
        val response =
            send(
                HttpRequest
                    .newBuilder(URI.create(key.tokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form)),
            )
        if (response.statusCode() !in OK_RANGE) throw failure("token", response)
        val body =
            try {
                json.decodeFromString(TokenBody.serializer(), response.body())
            } catch (e: SerializationException) {
                throw PlayApiException("Google's token response is unreadable", response.statusCode(), e)
            }
        return CachedToken(body.accessToken, now.plusSeconds(body.expiresIn))
    }

    private fun parseSubscription(body: String): PlaySubscription {
        val dto =
            try {
                json.decodeFromString(SubscriptionBody.serializer(), body)
            } catch (e: SerializationException) {
                throw PlayApiException("Google's subscription response is unreadable", cause = e)
            }
        return PlaySubscription(
            state = dto.subscriptionState ?: "SUBSCRIPTION_STATE_UNSPECIFIED",
            acknowledged = dto.acknowledgementState == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED",
            testPurchase = dto.testPurchase != null,
            linkedPurchaseToken = dto.linkedPurchaseToken,
            obfuscatedAccountId = dto.externalAccountIdentifiers?.obfuscatedExternalAccountId,
            lineItems =
                dto.lineItems.map { item ->
                    PlayLineItem(
                        productId = item.productId.orEmpty(),
                        basePlanId = item.offerDetails?.basePlanId,
                        expiryTime = item.expiryTime?.let(::instantOrNull),
                        autoRenewing = item.autoRenewingPlan?.autoRenewEnabled == true,
                    )
                },
        )
    }

    // Google's error bodies aren't described in the discovery document, so only the status is logged.
    private fun failure(
        call: String,
        response: HttpResponse<String>,
    ): PlayApiException {
        log.warn("Google Play {} failed: HTTP {}", call, response.statusCode())
        return PlayApiException("Google Play $call returned HTTP ${response.statusCode()}", response.statusCode())
    }

    @Serializable
    private data class TokenBody(
        @SerialName("access_token") val accessToken: String,
        @SerialName("expires_in") val expiresIn: Long = DEFAULT_TOKEN_SECONDS,
    )

    @Serializable
    private data class SubscriptionBody(
        val subscriptionState: String? = null,
        val acknowledgementState: String? = null,
        val testPurchase: TestPurchaseBody? = null,
        val linkedPurchaseToken: String? = null,
        val externalAccountIdentifiers: AccountIdsBody? = null,
        val lineItems: List<LineItemBody> = emptyList(),
    )

    // `TestPurchase` has no fields of interest; its presence marks a license tester's purchase.
    @Serializable
    private class TestPurchaseBody

    @Serializable
    private data class AccountIdsBody(
        val obfuscatedExternalAccountId: String? = null,
    )

    @Serializable
    private data class LineItemBody(
        val productId: String? = null,
        val expiryTime: String? = null,
        val autoRenewingPlan: AutoRenewingBody? = null,
        val offerDetails: OfferBody? = null,
    )

    @Serializable
    private data class AutoRenewingBody(
        val autoRenewEnabled: Boolean? = null,
    )

    @Serializable
    private data class OfferBody(
        val basePlanId: String? = null,
    )

    private companion object {
        const val SCOPE = "https://www.googleapis.com/auth/androidpublisher"
        const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
        const val ASSERTION_LIFETIME_SECONDS = 3600L
        const val DEFAULT_TOKEN_SECONDS = 3600L
        const val TOKEN_MARGIN_SECONDS = 60L
        const val UNAUTHORIZED = 401
        const val NOT_FOUND = 404
        const val GONE = 410
        val OK_RANGE = 200..299
        val TIMEOUT = 10.seconds
        val json = Json { ignoreUnknownKeys = true }
        val log = LoggerFactory.getLogger(GooglePlayDeveloperApi::class.java)

        fun segment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        fun instantOrNull(text: String): Instant? =
            try {
                Instant.parse(text)
            } catch (e: DateTimeParseException) {
                log.warn("Unreadable expiryTime from Google Play", e)
                null
            }
    }
}
