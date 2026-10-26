package io.github.jeshiomurmu.opencaller.core.telephony.lookup

import io.github.jeshiomurmu.opencaller.core.data.lookup.LiveLookupRepository
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.screening.NetworkLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.NetworkVerdict
import javax.inject.Inject

/** Step 10 of the screening engine on top of [LiveLookupRepository] (k-anonymous bucket lookup, 800 ms, cached 24 h). */
class ServerNetworkLookup
    @Inject
    constructor(
        private val repository: LiveLookupRepository,
    ) : NetworkLookup {
        override suspend fun lookup(number: PhoneNumber): NetworkVerdict? =
            repository.lookup(number)?.let {
                NetworkVerdict(
                    spamScore = it.spamScore,
                    category = it.category,
                    reportCount = it.reportCount,
                    displayName = it.displayName,
                    nameSource = it.nameSource,
                    fromCache = it.fromCache,
                )
            }
    }
