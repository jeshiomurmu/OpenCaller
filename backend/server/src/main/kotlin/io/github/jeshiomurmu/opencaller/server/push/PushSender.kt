package io.github.jeshiomurmu.opencaller.server.push

import io.github.jeshiomurmu.opencaller.server.config.FcmConfig
import io.github.jeshiomurmu.opencaller.server.config.VapidConfig
import io.github.jeshiomurmu.opencaller.server.google.GoogleApiException
import io.github.jeshiomurmu.opencaller.server.google.ServiceAccountTokens
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Where a device receives pushes. */
sealed interface PushTarget {
    /** An FCM registration token (play build). */
    data class Fcm(
        val token: String,
    ) : PushTarget

    /** A Web Push subscription (UnifiedPush, foss build): endpoint URL and the subscription's keys. */
    data class WebPush(
        val endpoint: URI,
        val p256dh: ByteArray,
        val auth: ByteArray,
    ) : PushTarget {
        override fun equals(other: Any?): Boolean = other is WebPush && other.endpoint == endpoint

        override fun hashCode(): Int = endpoint.hashCode()
    }
}

/** What happened to one push. [GONE]: the push service no longer knows the target, so it is removed. */
enum class PushOutcome { DELIVERED, GONE, FAILED, UNSUPPORTED }

/** Sends one push whose only content is [itemId] (ADR 0007). */
interface PushSender {
    /** Sends [itemId] to [target]; [urgent] pushes (calls) ask the push service to wake the phone now. */
    suspend fun send(
        target: PushTarget,
        itemId: UUID,
        ttlSeconds: Int,
        urgent: Boolean,
    ): PushOutcome
}

/**
 * FCM HTTP v1 (`POST v1/projects/{project}/messages:send`, discovery revision 20261023: `message.token`,
 * `message.data`, `android.priority`, `android.ttl`; scope `firebase.messaging`) and Web Push (RFC 8030 POST of an
 * RFC 8291 `aes128gcm` body with a VAPID `Authorization`, `TTL` and `Urgency`). Either may be unconfigured; a
 * target whose provider is missing gets [PushOutcome.UNSUPPORTED].
 */
class HttpPushSender(
    private val fcm: FcmConfig?,
    vapid: VapidConfig?,
    clock: Clock,
    private val random: SecureRandom = SecureRandom(),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT.toJavaDuration()).build(),
) : PushSender {
    private val fcmTokens = fcm?.let { ServiceAccountTokens(it.serviceAccount, FCM_SCOPE, clock, http) }
    private val vapidSigner = vapid?.let { VapidSigner(it, clock) }

    override suspend fun send(
        target: PushTarget,
        itemId: UUID,
        ttlSeconds: Int,
        urgent: Boolean,
    ): PushOutcome =
        try {
            when (target) {
                is PushTarget.Fcm -> sendFcm(target, itemId, ttlSeconds, urgent)
                is PushTarget.WebPush -> sendWebPush(target, itemId, ttlSeconds, urgent)
            }
        } catch (e: IOException) {
            log.warn("Push could not be sent ({})", e.javaClass.simpleName)
            PushOutcome.FAILED
        }

    private suspend fun sendFcm(
        target: PushTarget.Fcm,
        itemId: UUID,
        ttlSeconds: Int,
        urgent: Boolean,
    ): PushOutcome {
        val config = fcm ?: return PushOutcome.UNSUPPORTED
        val tokens = fcmTokens ?: return PushOutcome.UNSUPPORTED
        val body =
            buildJsonObject {
                putJsonObject("message") {
                    put("token", target.token)
                    putJsonObject("data") { put("id", itemId.toString()) }
                    putJsonObject("android") {
                        put("priority", if (urgent) "HIGH" else "NORMAL")
                        put("ttl", "${ttlSeconds}s")
                    }
                }
            }.toString()
        val uri = URI.create("${config.apiBaseUrl}/v1/projects/${config.projectId}/messages:send")
        val request = { token: String ->
            HttpRequest
                .newBuilder(uri)
                .timeout(TIMEOUT.toJavaDuration())
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        }
        val first = post(request(fcmToken(tokens, refresh = false)))
        val response = if (first.statusCode() == UNAUTHORIZED) post(request(fcmToken(tokens, refresh = true))) else first
        return outcome("FCM", response.statusCode())
    }

    private suspend fun sendWebPush(
        target: PushTarget.WebPush,
        itemId: UUID,
        ttlSeconds: Int,
        urgent: Boolean,
    ): PushOutcome {
        val signer = vapidSigner ?: return PushOutcome.UNSUPPORTED
        val payload = """{"id":"$itemId"}""".toByteArray()
        val body = WebPushCrypto.encrypt(payload, target.p256dh, target.auth, random)
        val request =
            HttpRequest
                .newBuilder(target.endpoint)
                .timeout(TIMEOUT.toJavaDuration())
                .header("Authorization", signer.authorization(target.endpoint))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", ttlSeconds.toString())
                .header("Urgency", if (urgent) "high" else "normal")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
        return outcome("Web Push", post(request).statusCode())
    }

    private suspend fun fcmToken(
        tokens: ServiceAccountTokens,
        refresh: Boolean,
    ): String =
        try {
            tokens.token(refresh)
        } catch (e: GoogleApiException) {
            throw IOException("FCM token: ${e.message}", e)
        }

    private suspend fun post(request: HttpRequest): HttpResponse<String> =
        http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()

    private fun outcome(
        provider: String,
        status: Int,
    ): PushOutcome =
        when (status) {
            in OK_RANGE -> {
                PushOutcome.DELIVERED
            }

            NOT_FOUND, GONE -> {
                PushOutcome.GONE
            }

            else -> {
                log.warn("{} push failed: HTTP {}", provider, status)
                PushOutcome.FAILED
            }
        }

    /** Decoding stored Web Push keys. */
    companion object {
        private const val FCM_SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val GONE = 410
        private val OK_RANGE = 200..299
        private val TIMEOUT = 5.seconds
        private val log = LoggerFactory.getLogger(HttpPushSender::class.java)

        /** base64url (padding optional) to bytes. */
        fun base64Url(text: String): ByteArray = Base64.getUrlDecoder().decode(text.trimEnd('='))
    }
}
