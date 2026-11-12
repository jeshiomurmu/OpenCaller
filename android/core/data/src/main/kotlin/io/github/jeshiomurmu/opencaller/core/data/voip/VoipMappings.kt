package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.model.AppCall
import io.github.jeshiomurmu.opencaller.core.model.AppCallRoom
import io.github.jeshiomurmu.opencaller.core.model.AppCallState
import io.github.jeshiomurmu.opencaller.core.model.FlashKind
import io.github.jeshiomurmu.opencaller.core.model.FlashMessage
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ServerFeatures
import io.github.jeshiomurmu.opencaller.core.network.CallInviteDto
import io.github.jeshiomurmu.opencaller.core.network.CallSessionDto
import io.github.jeshiomurmu.opencaller.core.network.FeaturesDto
import io.github.jeshiomurmu.opencaller.core.network.FlashDto
import io.github.jeshiomurmu.opencaller.core.network.RoomTokenDto
import java.time.Instant
import java.time.format.DateTimeParseException

/** Epoch millis of an ISO-8601 instant from the server, or [fallback] if it can't be read. */
internal fun instantMillis(
    value: String,
    fallback: Long,
): Long =
    try {
        Instant.parse(value).toEpochMilli()
    } catch (_: DateTimeParseException) {
        fallback
    }

internal fun phoneNumberOrNull(value: String?): PhoneNumber? = value?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)

internal fun CallInviteDto.toModel(nowMillis: Long): AppCall =
    AppCall(
        inviteId = inviteId,
        state = AppCallState.parse(state),
        isCaller = role == "caller",
        number = phoneNumberOrNull(number),
        name = name,
        videoId = videoId?.takeIf(VideoClips::isClipId),
        ringUntilMillis = instantMillis(ringUntil, nowMillis),
    )

internal fun CallSessionDto.toRoom(): AppCallRoom = AppCallRoom(inviteId, room, url, token)

internal fun RoomTokenDto.toRoom(inviteId: String): AppCallRoom = AppCallRoom(inviteId, room, url, token)

/** A received Flash message, or `null` for a kind or sender this app can't show. */
internal fun FlashDto.toModel(nowMillis: Long): FlashMessage? {
    val kind = FlashKind.parse(kind)
    val number = phoneNumberOrNull(from)
    return if (kind != null &&
        number != null
    ) {
        FlashMessage(id, kind, number, name, instantMillis(sentAt, nowMillis), outgoing = false)
    } else {
        null
    }
}

internal fun FeaturesDto.toModel(): ServerFeatures =
    ServerFeatures(
        appCalls,
        flash,
        fcm,
        webPushVapidKey?.takeIf {
            VAPID_KEY.matches(it)
        },
        assistant,
    )

// What UnifiedPush accepts: a base64url uncompressed P-256 point (87 characters).
private val VAPID_KEY = Regex("^[A-Za-z0-9_-]{87}$")
