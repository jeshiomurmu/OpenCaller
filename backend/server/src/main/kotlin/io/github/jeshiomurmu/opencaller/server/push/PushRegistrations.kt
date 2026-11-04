package io.github.jeshiomurmu.opencaller.server.push

import io.github.jeshiomurmu.opencaller.server.config.VapidConfig
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.isPlainText
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.ktor.http.HttpStatusCode

/** Checks a device's `PUT /v1/push` body. */
internal object PushRegistrations {
    /** `devices.push_type` of an FCM token. */
    const val FCM = "fcm"

    /** `devices.push_type` of a Web Push (UnifiedPush) endpoint. */
    const val WEB_PUSH = "webpush"
    private const val MAX_TOKEN_LENGTH = 4096
    private const val P256_KEY_BYTES = 65
    private const val AUTH_BYTES = 16

    /** [registration] as a push target, or 400 `invalid_push_endpoint`. */
    fun validated(
        registration: PushRegistration,
        policy: PushEndpointPolicy,
    ): PushTarget =
        when (registration.type) {
            FCM -> {
                val token = registration.token
                requireValid(token != null && isPlainText(token, MAX_TOKEN_LENGTH) && '\n' !in token) {
                    invalidEndpoint("An FCM registration token is required.")
                }
                PushTarget.Fcm(token!!)
            }

            WEB_PUSH -> {
                val uri = policy.check(registration.endpoint ?: throw invalidEndpoint("A Web Push endpoint is required."))
                val key = decoded(registration.p256dh, P256_KEY_BYTES, "p256dh must be a 65-byte P-256 key, base64url.")
                requireValid(isPublicKey(key)) { invalidEndpoint("p256dh is not a P-256 public key.") }
                val auth = decoded(registration.auth, AUTH_BYTES, "auth must be 16 bytes, base64url.")
                PushTarget.WebPush(uri, key, auth)
            }

            else -> {
                throw invalidEndpoint("type must be fcm or webpush.")
            }
        }

    private fun decoded(
        value: String?,
        size: Int,
        problem: String,
    ): ByteArray {
        val bytes =
            try {
                value?.let(HttpPushSender::base64Url)
            } catch (_: IllegalArgumentException) {
                null
            }
        requireValid(bytes != null && bytes.size == size) { invalidEndpoint(problem) }
        return bytes!!
    }

    private fun invalidEndpoint(detail: String) =
        ApiProblem(HttpStatusCode.BadRequest, "invalid_push_endpoint", "Invalid push endpoint", detail)

    private fun isPublicKey(bytes: ByteArray): Boolean =
        try {
            VapidConfig.publicKeyOf(bytes)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
}
