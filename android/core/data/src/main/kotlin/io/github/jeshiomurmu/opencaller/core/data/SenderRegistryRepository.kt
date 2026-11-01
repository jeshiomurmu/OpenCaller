package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.sync.LinkPrefixStore
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import javax.inject.Inject
import javax.inject.Singleton

/** A sender-ID registry entry: the brand behind a header and its category (`bank`, `telecom`, …). */
data class RegisteredSender(
    val brandName: String,
    val category: String,
    val source: String,
    val businessId: String?,
)

/**
 * Reads the registries [io.github.jeshiomurmu.opencaller.core.data.sync.RegistrySyncRepository] keeps on the phone:
 * sender IDs, SMS Retriever app hashes, flash-call verification ranges and the known-bad link prefixes. Lookups
 * never leave the device.
 */
@Singleton
class SenderRegistryRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val linkPrefixes: LinkPrefixStore,
    ) {
        private val storage = Storage(access, dispatchers)

        /** The registry entry for [header] in [countryIso], or `null`. */
        suspend fun sender(
            countryIso: String,
            header: String,
        ): AppResult<RegisteredSender?> =
            storage
                .run { senderIdDao().find(countryIso.uppercase(), header.uppercase()) }
                .map { row -> row?.let { RegisteredSender(it.brandName, it.category, it.source, it.businessId) } }

        /** The name of the app whose SMS Retriever hash is [hash], or `null`. */
        suspend fun appForHash(hash: String): AppResult<String?> = storage.run { otpAppHashDao().find(hash)?.appName }

        /** The flash-call verification provider whose range [number] is in, or `null`. */
        suspend fun verificationProvider(number: PhoneNumber): AppResult<String?> =
            storage.run { verificationRangeDao().findLongestPrefix(number.e164)?.provider }

        /** True when [prefix] (a host's hash prefix) is on the known-bad link list. */
        suspend fun isBadHostPrefix(prefix: Int): Boolean = linkPrefixes.contains(prefix)
    }
