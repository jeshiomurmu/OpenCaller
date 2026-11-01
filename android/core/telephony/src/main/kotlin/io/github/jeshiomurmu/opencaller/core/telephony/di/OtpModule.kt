package io.github.jeshiomurmu.opencaller.core.telephony.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.MessageMetaRepository
import io.github.jeshiomurmu.opencaller.core.data.RegisteredSender
import io.github.jeshiomurmu.opencaller.core.data.SenderRegistryRepository
import io.github.jeshiomurmu.opencaller.core.ml.link.BadHostPrefixes
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.SenderRegistry
import io.github.jeshiomurmu.opencaller.core.telephony.otp.CallParties
import io.github.jeshiomurmu.opencaller.core.telephony.otp.CallParty
import io.github.jeshiomurmu.opencaller.core.telephony.otp.RecentOtps
import io.github.jeshiomurmu.opencaller.core.telephony.otp.VerificationRanges
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerLookup

/**
 * The OTP guard's and the sender resolver's views of `core:data` (registries, message metadata) and of the caller
 * lookup. A failed read counts as "not found": the guard and the resolver never stop a message.
 */
@Module
@InstallIn(SingletonComponent::class)
internal object OtpModule {
    @Provides
    fun senderRegistry(registry: SenderRegistryRepository): SenderRegistry =
        object : SenderRegistry {
            override suspend fun sender(
                countryIso: String,
                header: String,
            ): RegisteredSender? = registry.sender(countryIso, header).getOrNull()

            override suspend fun appForHash(hash: String): String? = registry.appForHash(hash).getOrNull()
        }

    @Provides
    fun badHostPrefixes(registry: SenderRegistryRepository): BadHostPrefixes = BadHostPrefixes { registry.isBadHostPrefix(it) }

    @Provides
    fun verificationRanges(registry: SenderRegistryRepository): VerificationRanges =
        VerificationRanges { registry.verificationProvider(it).getOrNull() }

    // The conversation stands for the sender: one thread per address.
    @Provides
    fun recentOtps(meta: MessageMetaRepository): RecentOtps =
        RecentOtps { since ->
            meta
                .otpsSince(since)
                .getOrNull()
                .orEmpty()
                .map { (at, thread) -> at to "thread:$thread" }
        }

    @Provides
    fun callParties(lookup: CallerLookup): CallParties =
        CallParties { handle, hidden ->
            val caller = if (hidden) null else lookup.describe(handle)
            val party = if (caller?.nameSource == NameSource.CONTACT) CallParty.CONTACT else CallParty.STRANGER
            caller?.number to party
        }
}
