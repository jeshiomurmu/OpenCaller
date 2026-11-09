package io.github.jeshiomurmu.opencaller.core.data.family

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.Storage
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlert
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.network.FamilyAlertDto
import io.github.jeshiomurmu.opencaller.core.network.FamilyApi
import io.github.jeshiomurmu.opencaller.core.network.FamilyCountsDto
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import timber.log.Timber
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** How a family send went: done (or nothing to send), try again later, or dropped for good. */
enum class FamilySendOutcome {
    DONE,
    RETRY,
    DROPPED,
}

/** Where the OTP guard reports that it warned the user (family protection); a no-op unless the member shares alerts. */
fun interface OtpWarningReport {
    /** The guard showed a warning of [kind] on this phone. */
    suspend fun warned(kind: FamilyAlertKind)
}

/**
 * Tells the admin that the OTP guard warned this member (TC-FAM-002). Only the alert's kind leaves the phone, and
 * only while this member shares OTP alerts; the guard's own warning on this phone doesn't depend on it.
 */
@Singleton
class FamilyAlertReporter
    @Inject
    constructor(
        private val api: FamilyApi,
        private val preferences: FamilyPreferences,
        private val work: FamilyWork,
        private val clock: Clock,
    ) : OtpWarningReport {
        /** The guard warned this user: queues the alert if the member shares alerts, else does nothing. */
        override suspend fun warned(kind: FamilyAlertKind) {
            if (preferences.current().sendsAlerts) work.sendAlert(kind, clock.nowMillis())
        }

        /** Sends a queued alert raised at [atMillis]; one older than an hour is no longer worth a push. */
        suspend fun send(
            kind: FamilyAlertKind,
            atMillis: Long,
        ): FamilySendOutcome {
            val stale = clock.nowMillis() - atMillis > MAX_AGE_MILLIS
            if (stale || !preferences.current().sendsAlerts) return FamilySendOutcome.DROPPED
            return when (val result = apiCall { api.alert(FamilyAlertDto(kind.wireName)) }) {
                is AppResult.Success -> {
                    Timber.i("Family alert sent (%d of the admin's devices reached)", result.value.reachedDevices)
                    FamilySendOutcome.DONE
                }

                is AppResult.Failure -> {
                    outcomeOf(result.error)
                }
            }
        }

        private companion object {
            const val MAX_AGE_MILLIS = 60L * 60 * 1000
        }
    }

/**
 * Sends yesterday's two counts (calls blocked or silenced, messages filtered as spam or phishing), computed from this
 * phone's own screening log and message categories, while the member shares daily counts. No number, name or text.
 */
@Singleton
class FamilyCountsReporter
    @Inject
    constructor(
        private val api: FamilyApi,
        private val preferences: FamilyPreferences,
        private val clock: Clock,
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
    ) {
        private val storage = Storage(access, dispatchers)

        /** Sends yesterday's counts in [zone] unless they were sent already. */
        suspend fun sendYesterday(zone: ZoneId): FamilySendOutcome {
            val day =
                Instant
                    .ofEpochMilli(clock.nowMillis())
                    .atZone(zone)
                    .toLocalDate()
                    .minusDays(1)
            return when {
                !preferences.current().sendsCounts -> FamilySendOutcome.DROPPED
                preferences.lastCountsDay() == day.toString() -> FamilySendOutcome.DONE
                else -> send(day, zone)
            }
        }

        private suspend fun send(
            day: LocalDate,
            zone: ZoneId,
        ): FamilySendOutcome {
            val counts = countsOf(day, zone) ?: return FamilySendOutcome.RETRY
            return when (val result = apiCallNoContent { api.counts(day.toString(), counts) }) {
                is AppResult.Success -> {
                    preferences.setLastCountsDay(day.toString())
                    FamilySendOutcome.DONE
                }

                is AppResult.Failure -> {
                    outcomeOf(result.error)
                }
            }
        }

        /** [day]'s counts on this phone, or `null` if the database couldn't be read. */
        suspend fun countsOf(
            day: LocalDate,
            zone: ZoneId,
        ): FamilyCountsDto? {
            val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val to =
                day
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli()
            val result =
                storage.run {
                    FamilyCountsDto(
                        blockedCalls = screeningEventDao().countBlocked(from, to).coerceAtMost(MAX_COUNT),
                        spamMessages = messageMetaDao().count(FILTERED, from, to).coerceAtMost(MAX_COUNT),
                    )
                }
            return (result as? AppResult.Success)?.value
        }

        private companion object {
            const val MAX_COUNT = 10_000
            val FILTERED = listOf(MessageCategory.SPAM, MessageCategory.PHISHING)
        }
    }

// Offline or a server error: try again. Rate-limited, signed out, no longer sharing or not in the group: drop.
private fun outcomeOf(error: AppError): FamilySendOutcome =
    when (error) {
        AppError.Network, is AppError.Unexpected -> FamilySendOutcome.RETRY
        else -> FamilySendOutcome.DROPPED
    }

/** Shows the admin that a member's phone warned about a one-time code (a notification); `feature:family`. */
fun interface FamilyAlertNotices {
    /** Tells the admin about [alert]. */
    fun show(alert: FamilyAlert)
}
