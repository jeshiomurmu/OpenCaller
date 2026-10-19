package io.github.jeshiomurmu.opencaller.core.data.sync

import kotlinx.coroutines.flow.Flow

/**
 * What the phone has: the list of [country] at [version], last checked at [checkedAt] (epoch millis). All `null`
 * before the first successful update.
 */
data class SpamListSyncState(
    val country: String? = null,
    val version: Long? = null,
    val checkedAt: Long? = null,
)

/** The spam list's update state for settings screens, and "Update now". */
interface SpamListUpdates {
    /** Country and version of the list on this phone, and when the server was last asked. */
    val state: Flow<SpamListSyncState>

    /** True when this build has a server to update from. */
    val serverConfigured: Boolean

    /** Updates the list now, on any network. */
    fun updateNow()
}
