package io.github.jeshiomurmu.opencaller.core.data.profile

import io.github.jeshiomurmu.opencaller.core.model.MyProfile
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisibility
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisit
import io.github.jeshiomurmu.opencaller.core.model.ProfileVisits
import io.github.jeshiomurmu.opencaller.core.model.SupporterBadge
import io.github.jeshiomurmu.opencaller.core.model.VisitKind
import io.github.jeshiomurmu.opencaller.core.network.MeDto
import io.github.jeshiomurmu.opencaller.core.network.VisitorsDto
import timber.log.Timber
import java.time.Instant
import java.time.format.DateTimeParseException

// Server DTOs → models for ProfileRepository.

internal fun MeDto.toProfile() =
    MyProfile(
        number = PhoneNumber(e164),
        displayName = displayName,
        avatarKey = avatarKey,
        avatarUrl = avatarUrl,
        visibility =
            ProfileVisibility.entries.firstOrNull { it.name.equals(visibility, ignoreCase = true) } ?: ProfileVisibility.NOBODY,
        incognito = incognito,
        badge = SupporterBadge.parse(badge),
        supporter = supporter,
        viewerCount = viewerCount,
        searcherCount = searcherCount,
        pendingContactRequests = pendingContactRequests,
    )

internal fun VisitorsDto.toVisits(kind: VisitKind) =
    ProfileVisits(
        kind = kind,
        days = days,
        total = total,
        items =
            items.map {
                ProfileVisit(
                    it.name,
                    SupporterBadge.parse(it.badge).takeIf { _ ->
                        it.name != null
                    },
                    millisOf(it.at),
                )
            },
    )

// The server sends ISO-8601 instants; an unreadable one shows as "unknown time" (0) rather than failing the list.
internal fun millisOf(iso: String): Long =
    try {
        Instant.parse(iso).toEpochMilli()
    } catch (e: DateTimeParseException) {
        Timber.w(e, "Unreadable time from the server")
        0L
    }
