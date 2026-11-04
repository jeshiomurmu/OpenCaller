package io.github.jeshiomurmu.opencaller.server.config

import java.nio.file.Path

private const val MAX_TOKEN_TTL_SECONDS = 3600
private const val MIN_TOKEN_TTL_SECONDS = 60
private const val MAX_FFMPEG_TIMEOUT_SECONDS = 600
private const val MAX_POLL_SECONDS = 600
private val API_KEY = Regex("[A-Za-z0-9_-]{3,64}")
private val FIREBASE_PROJECT = Regex("[a-z][a-z0-9-]{4,62}")

/** The `voip` section: each part is off unless its first variable (`LIVEKIT_URL`, `FCM_*`, `VAPID_PUBLIC_KEY`) is set. */
internal fun ConfigReader.readVoip(environment: Environment?): VoipConfig =
    VoipConfig(
        liveKit = readLiveKit(environment),
        push = PushConfig(fcm = readFcm(environment), webPush = readVapid()),
        video =
            VideoConfig(
                ffmpegPath = string("voip.video.ffmpegPath") ?: "ffmpeg",
                timeoutSeconds = int("voip.video.timeoutSeconds", "FFMPEG_TIMEOUT_SECONDS", 1..MAX_FFMPEG_TIMEOUT_SECONDS).toLong(),
                pollSeconds = int("voip.video.pollSeconds", "TRANSCODER_POLL_SECONDS", 1..MAX_POLL_SECONDS).toLong(),
            ),
    )

private fun ConfigReader.readLiveKit(environment: Environment?): LiveKitConfig? {
    string("voip.livekit.url") ?: return null
    val url = prefixed("voip.livekit.url", "LIVEKIT_URL", "wss://", "ws://")
    if (environment == Environment.PRODUCTION && url.startsWith("ws://")) problems += "LIVEKIT_URL must use wss:// in production"
    val key = required("voip.livekit.apiKey", "LIVEKIT_API_KEY")
    if (key.isNotEmpty() && !API_KEY.matches(key)) problems += "LIVEKIT_API_KEY must be 3 to 64 letters, digits, dashes or underscores"
    return LiveKitConfig(
        url = url.trimEnd('/'),
        apiKey = key,
        apiSecret = secret("voip.livekit.apiSecret", "LIVEKIT_API_SECRET"),
        tokenTtlSeconds = int("voip.livekit.tokenTtlSeconds", "LIVEKIT_TOKEN_TTL_SECONDS", MIN_TOKEN_TTL_SECONDS..MAX_TOKEN_TTL_SECONDS).toLong(),
    )
}

// FCM is on when a service-account key file is configured; then the Firebase project ID is required.
private fun ConfigReader.readFcm(environment: Environment?): FcmConfig? {
    val keyFile = string("voip.fcm.serviceAccountFile") ?: return null
    val key =
        try {
            ServiceAccountKey.read(Path.of(keyFile))
        } catch (e: IllegalArgumentException) {
            problems += "FCM_SERVICE_ACCOUNT_FILE ${e.message}"
            null
        }
    if (key != null && environment == Environment.PRODUCTION && !key.tokenUri.startsWith("https://")) {
        problems += "FCM_SERVICE_ACCOUNT_FILE: token_uri must use https in production"
    }
    val project = required("voip.fcm.projectId", "FCM_PROJECT_ID")
    if (project.isNotEmpty() && !FIREBASE_PROJECT.matches(project)) problems += "FCM_PROJECT_ID must be a Firebase project ID"
    val baseUrl = prefixed("voip.fcm.apiBaseUrl", "FCM_API_BASE_URL", "https://", "http://")
    return key?.let { FcmConfig(project, it, baseUrl.trimEnd('/')) }
}

// Web Push (UnifiedPush) is on when a VAPID key pair is configured; then a contact subject is required too.
private fun ConfigReader.readVapid(): VapidConfig? {
    val public = string("voip.vapid.publicKey") ?: return null
    val private = required("voip.vapid.privateKey", "VAPID_PRIVATE_KEY")
    val subject = prefixed("voip.vapid.subject", "VAPID_SUBJECT", "mailto:", "https://")
    if (private.isEmpty()) return null
    return try {
        VapidConfig.parse(public, private, subject)
    } catch (e: IllegalArgumentException) {
        problems += e.message ?: "VAPID keys are invalid"
        null
    }
}
