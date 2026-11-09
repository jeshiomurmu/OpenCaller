package io.github.jeshiomurmu.opencaller.core.data.family

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * This phone's own family membership as last seen on the server: whether the user is in a group, whether they are
 * its admin, and what they share. The OTP guard and the daily counts worker read it without a network call, so
 * nothing is sent unless the member switched it on (ADR 0008).
 */
data class FamilyMembership(
    val inGroup: Boolean,
    val isAdmin: Boolean,
    val sharing: FamilySharing,
) {
    /** True when this phone may send an alert's kind to the admin. */
    val sendsAlerts: Boolean get() = inGroup && !isAdmin && sharing.otpAlerts

    /** True when this phone may send its daily counts to the admin. */
    val sendsCounts: Boolean get() = inGroup && !isAdmin && sharing.dailyCounts

    /** Outside any group. */
    companion object {
        /** Not in a group, nothing shared. */
        val NONE = FamilyMembership(inGroup = false, isAdmin = false, sharing = FamilySharing.NONE)
    }
}

/** [FamilyMembership] in DataStore, and the last day whose counts were sent. */
class FamilyPreferences(
    private val store: DataStore<Preferences>,
) {
    /** The membership as last seen. */
    val membership: Flow<FamilyMembership> =
        store.data.map {
            FamilyMembership(
                inGroup = it[IN_GROUP] ?: false,
                isAdmin = it[ADMIN] ?: false,
                sharing = FamilySharing(otpAlerts = it[OTP_ALERTS] ?: false, dailyCounts = it[DAILY_COUNTS] ?: false),
            )
        }

    /** The membership now. */
    suspend fun current(): FamilyMembership = membership.first()

    /** Remembers [membership]; leaving a group also forgets the last counts day. */
    suspend fun set(membership: FamilyMembership) {
        store.edit {
            it[IN_GROUP] = membership.inGroup
            it[ADMIN] = membership.isAdmin
            it[OTP_ALERTS] = membership.sharing.otpAlerts
            it[DAILY_COUNTS] = membership.sharing.dailyCounts
            if (!membership.sendsCounts) it.remove(LAST_COUNTS_DAY)
        }
    }

    /** The last day (`yyyy-mm-dd`) whose counts reached the server, or `null`. */
    suspend fun lastCountsDay(): String? = store.data.first()[LAST_COUNTS_DAY]

    /** Remembers that [day]'s counts reached the server. */
    suspend fun setLastCountsDay(day: String) {
        store.edit { it[LAST_COUNTS_DAY] = day }
    }

    /** File name and keys. */
    companion object {
        /** DataStore file name. */
        const val FILE_NAME = "family"
        private val IN_GROUP = booleanPreferencesKey("in_group")
        private val ADMIN = booleanPreferencesKey("admin")
        private val OTP_ALERTS = booleanPreferencesKey("share_otp_alerts")
        private val DAILY_COUNTS = booleanPreferencesKey("share_daily_counts")
        private val LAST_COUNTS_DAY = stringPreferencesKey("last_counts_day")
    }
}
