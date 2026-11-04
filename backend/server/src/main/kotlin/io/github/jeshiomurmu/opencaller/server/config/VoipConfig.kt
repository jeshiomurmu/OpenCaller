package io.github.jeshiomurmu.opencaller.server.config

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import java.util.Base64

/**
 * App calls, push and video caller ID (Phase 11). Without [liveKit] app calls are off (`POST /v1/voip/calls` answers
 * 503); without any push provider in [push] nobody can be rung, so app calls and Flash messages are off as well.
 */
data class VoipConfig(
    val liveKit: LiveKitConfig? = null,
    val push: PushConfig = PushConfig(),
    val video: VideoConfig = VideoConfig(),
)

/**
 * The LiveKit server: [url] is where apps connect (`wss://` in production), [apiKey] and [apiSecret] sign access
 * tokens (LiveKit's `keys` setting), valid for [tokenTtlSeconds].
 */
data class LiveKitConfig(
    val url: String,
    val apiKey: String,
    val apiSecret: String,
    val tokenTtlSeconds: Long = DEFAULT_TOKEN_TTL_SECONDS,
) {
    override fun toString() = "LiveKitConfig(url=$url, apiKey=$apiKey, apiSecret=***, tokenTtlSeconds=$tokenTtlSeconds)"

    /** Defaults. */
    companion object {
        /** Ten minutes: enough to join; a joined participant stays connected after the token expires. */
        const val DEFAULT_TOKEN_TTL_SECONDS = 600L
    }
}

/** Push providers: FCM for the play build, Web Push (UnifiedPush) for foss. Either may be off. */
data class PushConfig(
    val fcm: FcmConfig? = null,
    val webPush: VapidConfig? = null,
) {
    /** True when at least one provider can deliver. */
    val enabled: Boolean get() = fcm != null || webPush != null
}

/** FCM HTTP v1 for one Firebase [projectId], authorized by a service account with the `firebase.messaging` scope. */
data class FcmConfig(
    val projectId: String,
    val serviceAccount: ServiceAccountKey,
    val apiBaseUrl: String = DEFAULT_API_BASE_URL,
) {
    /** Google's host. */
    companion object {
        /** Production FCM. */
        const val DEFAULT_API_BASE_URL = "https://fcm.googleapis.com"
    }
}

/**
 * The server's VAPID identity for Web Push (RFC 8292): a P-256 key pair and a contact [subject] (`mailto:` or
 * `https:`). Apps pass [publicKeyBase64Url] to their UnifiedPush distributor when they register.
 */
class VapidConfig(
    val publicKey: ECPublicKey,
    val privateKey: ECPrivateKey,
    val subject: String,
) {
    /** The uncompressed public key (65 bytes), base64url without padding, as UnifiedPush and RFC 8292 expect it. */
    val publicKeyBase64Url: String = Base64.getUrlEncoder().withoutPadding().encodeToString(uncompressed(publicKey))

    override fun toString() = "VapidConfig(publicKey=$publicKeyBase64Url, privateKey=***, subject=$subject)"

    /** Building keys from their base64url forms. */
    companion object {
        private const val COORDINATE_BYTES = 32
        private const val UNCOMPRESSED_BYTES = 65
        private const val UNCOMPRESSED_PREFIX: Byte = 4

        /** The P-256 curve. */
        val P256: ECParameterSpec =
            AlgorithmParameters
                .getInstance("EC")
                .apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)

        /**
         * Parses an uncompressed public key (65 bytes) and a raw private key (32 bytes), both base64url; throws
         * [IllegalArgumentException] if they are malformed or don't belong together.
         */
        fun parse(
            publicKey: String,
            privateKey: String,
            subject: String,
        ): VapidConfig {
            val rawPublic = decode(publicKey, "VAPID_PUBLIC_KEY")
            require(rawPublic.size == UNCOMPRESSED_BYTES) { "VAPID_PUBLIC_KEY must be a 65-byte uncompressed P-256 key, base64url" }
            val pub = publicKeyOf(rawPublic)
            val rawPrivate = decode(privateKey, "VAPID_PRIVATE_KEY")
            require(rawPrivate.size == COORDINATE_BYTES) { "VAPID_PRIVATE_KEY must be 32 bytes, base64url" }
            val priv =
                KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, rawPrivate), P256)) as ECPrivateKey
            require(belongTogether(pub, priv)) { "VAPID_PUBLIC_KEY and VAPID_PRIVATE_KEY are not one key pair" }
            return VapidConfig(pub, priv, subject)
        }

        /** An uncompressed P-256 point (`04 || x || y`) as a public key. */
        fun publicKeyOf(bytes: ByteArray): ECPublicKey {
            require(bytes.size == UNCOMPRESSED_BYTES && bytes[0] == UNCOMPRESSED_PREFIX) {
                "a P-256 public key must be 65 bytes, uncompressed"
            }
            val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORDINATE_BYTES))
            val y = BigInteger(1, bytes.copyOfRange(1 + COORDINATE_BYTES, UNCOMPRESSED_BYTES))
            return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), P256)) as ECPublicKey
        }

        /** `04 || x || y` with both coordinates left-padded to 32 bytes. */
        fun uncompressed(key: ECPublicKey): ByteArray = byteArrayOf(UNCOMPRESSED_PREFIX) + fixed(key.w.affineX) + fixed(key.w.affineY)

        private fun fixed(value: BigInteger): ByteArray {
            val bytes = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
            return ByteArray(COORDINATE_BYTES - bytes.size) + bytes
        }

        private fun decode(
            text: String,
            name: String,
        ): ByteArray =
            try {
                Base64.getUrlDecoder().decode(text.trimEnd('='))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$name is not base64url", e)
            }

        private fun belongTogether(
            pub: ECPublicKey,
            priv: ECPrivateKey,
        ): Boolean {
            val probe = "opencaller-vapid-check".toByteArray()
            val signature =
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(priv)
                    update(probe)
                    sign()
                }
            return Signature.getInstance("SHA256withECDSA").run {
                initVerify(pub)
                update(probe)
                verify(signature)
            }
        }
    }
}

/**
 * Video caller ID transcoding (the `transcoder` worker): [ffmpegPath] runs each job, at most [timeoutSeconds] long;
 * the worker looks for new jobs every [pollSeconds].
 */
data class VideoConfig(
    val ffmpegPath: String = "ffmpeg",
    val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    val pollSeconds: Long = DEFAULT_POLL_SECONDS,
) {
    /** Defaults. */
    companion object {
        /** One minute per clip of at most ten seconds. */
        const val DEFAULT_TIMEOUT_SECONDS = 60L

        /** Five seconds between empty polls. */
        const val DEFAULT_POLL_SECONDS = 5L
    }
}
