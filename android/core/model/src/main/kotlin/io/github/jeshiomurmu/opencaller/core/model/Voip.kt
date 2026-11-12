package io.github.jeshiomurmu.opencaller.core.model

/** The predefined Flash messages (no free text, no location); [wireName] is the server's `kind`. */
enum class FlashKind(
    val wireName: String,
) {
    CALL_ME("call_me"),
    ON_MY_WAY("on_my_way"),
    RUNNING_LATE("running_late"),
    CANT_TALK("cant_talk"),
    ARRIVED("arrived"),
    CALL_YOU_BACK("call_you_back"),
    ;

    /** Lookup by the server's name. */
    companion object {
        /** The kind named [value], or `null` for one this app doesn't know (a newer server). */
        fun parse(value: String?): FlashKind? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * A Flash message kept on this phone only (the server deletes it once fetched). [number] is the other party: the
 * sender of a received message, the recipient of a sent one ([outgoing]).
 */
data class FlashMessage(
    val id: String,
    val kind: FlashKind,
    val number: PhoneNumber,
    val name: String?,
    val atMillis: Long,
    val outgoing: Boolean,
)

/** An app call's state on the server. */
enum class AppCallState {
    RINGING,
    ACCEPTED,
    DECLINED,
    CANCELLED,
    MISSED,
    ENDED,
    ;

    /** True once the call can't ring or be answered any more. */
    val isOver: Boolean get() = this != RINGING && this != ACCEPTED

    /** Lookup by the server's name. */
    companion object {
        /** The state named [value] (`ringing`, …); an unknown one counts as [ENDED]. */
        fun parse(value: String): AppCallState = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ENDED
    }
}

/**
 * An app call as this phone sees it: [number], [name] and [videoId] describe the other party; [isCaller] is true on
 * the phone that called.
 */
data class AppCall(
    val inviteId: String,
    val state: AppCallState,
    val isCaller: Boolean,
    val number: PhoneNumber?,
    val name: String?,
    val videoId: String?,
    val ringUntilMillis: Long,
)

/** A LiveKit room to join: [url] (ws:// or wss://), [room] and an access [token] for it. */
data class AppCallRoom(
    val inviteId: String,
    val room: String,
    val url: String,
    val token: String,
)

/** What the server offers (`GET /v1/server`); a server before Phase 11 offers none of it ([assistant]: Phase 13). */
data class ServerFeatures(
    val appCalls: Boolean = false,
    val flash: Boolean = false,
    val fcm: Boolean = false,
    val webPushVapidKey: String? = null,
    val assistant: Boolean = false,
)

/** Where pushes for this device go. */
sealed interface PushEndpoint {
    /** Firebase Cloud Messaging (play builds). */
    data class Fcm(
        val token: String,
    ) : PushEndpoint

    /** Web Push through a UnifiedPush distributor (foss builds): [p256dh] and [auth] are base64url. */
    data class WebPush(
        val url: String,
        val p256dh: String,
        val auth: String,
    ) : PushEndpoint
}

/** The user's own video caller ID: the published clip (if any) and a new upload's progress. */
data class MyVideoCallerId(
    val videoId: String?,
    val job: VideoJob,
    val error: String?,
    val hidden: Boolean,
)

/** Where a new clip is on its way to being published. */
enum class VideoJob {
    NONE,
    UPLOADING,
    QUEUED,
    PROCESSING,
    FAILED,
    ;

    /** Lookup by the server's name. */
    companion object {
        /** The job state named [value]; unknown ones count as [NONE]. */
        fun parse(value: String): VideoJob = entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: NONE
    }
}
