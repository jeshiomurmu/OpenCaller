package io.github.jeshiomurmu.opencaller.server.voip

import kotlinx.serialization.Serializable

/** `POST /v1/voip/calls`: call [to] (E.164) over the app. */
@Serializable
data class StartCallRequest(
    val to: String,
)

/**
 * A started app call: the caller joins [room] on [url] with [token] right away and waits for the callee.
 * [reachedDevices] is how many of the callee's phones the push reached (0: they may still poll).
 */
@Serializable
data class CallSession(
    val inviteId: String,
    val room: String,
    val url: String,
    val token: String,
    val tokenExpiresAt: String,
    val ringUntil: String,
    val reachedDevices: Int,
)

/**
 * One app call as one of its two parties sees it. [number], [name] and [videoId] describe the other party (name and
 * clip only as their profile allows, and the clip only for the callee). [state] is `ringing`, `accepted`,
 * `declined`, `cancelled`, `missed` or `ended`; [role] is `caller` or `callee`.
 */
@Serializable
data class InviteView(
    val inviteId: String,
    val state: String,
    val role: String,
    val number: String,
    val name: String? = null,
    val videoId: String? = null,
    val ringUntil: String,
)

/** `POST /v1/voip/token`: a LiveKit token for the room of call [inviteId]. */
@Serializable
data class RoomTokenRequest(
    val inviteId: String,
)

/** A LiveKit room to join. */
@Serializable
data class RoomToken(
    val room: String,
    val url: String,
    val token: String,
    val expiresAt: String,
)

/** `GET`/`PUT /v1/voip/settings`: whether app calls and Flash messages can reach this user. */
@Serializable
data class VoipSettings(
    val reachable: Boolean,
)

/** What one party does to a call. */
enum class CallAction {
    ACCEPT,
    DECLINE,
    CANCEL,
    END,
}
