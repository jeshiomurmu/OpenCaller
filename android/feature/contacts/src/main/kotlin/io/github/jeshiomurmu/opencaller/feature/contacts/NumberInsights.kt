package io.github.jeshiomurmu.opencaller.feature.contacts

import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerLookup
import javax.inject.Inject

/**
 * What the device knows about a contact's number apart from the contact itself: the spam list, verified businesses,
 * country and line type. On-device only, never the network.
 */
fun interface NumberInsights {
    /** The details for [dialString]; `null` when it isn't a phone number (or is an emergency number). */
    suspend fun about(dialString: String): CallerInfo?
}

/** [NumberInsights] from the in-call screen's [CallerLookup], skipping the contacts. */
class LookupNumberInsights
    @Inject
    constructor(
        private val lookup: CallerLookup,
    ) : NumberInsights {
        override suspend fun about(dialString: String): CallerInfo? =
            lookup.describe(dialString, includeContacts = false).takeIf {
                it.number !=
                    null
            }
    }
