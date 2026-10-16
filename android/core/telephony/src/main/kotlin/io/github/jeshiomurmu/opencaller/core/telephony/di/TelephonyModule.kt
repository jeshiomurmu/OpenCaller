package io.github.jeshiomurmu.opencaller.core.telephony.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ReputationRepository
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.data.SpamListRepository
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceAccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.screening.DeviceSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ListSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.NoNetworkLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleValidator
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningConfig
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEngine
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEventListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningLog
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningSourceException
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningTestHooks
import io.github.jeshiomurmu.opencaller.core.telephony.screening.TimeLimitedRegex
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.AndroidContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.DeviceHomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.LocalVerifiedBusinessLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.device.TelephonyEmergencyNumbers
import kotlinx.coroutines.flow.first
import javax.inject.Singleton
import kotlin.time.TimeSource

/** Binds the telephony interfaces to their device implementations. */
@Module
@InstallIn(SingletonComponent::class)
internal interface TelephonyModule {
    @Binds
    fun accessStatusSource(impl: DeviceAccessStatusSource): AccessStatusSource

    /** Debug builds bind QA hooks for the emulator script; release builds have none. */
    @BindsOptionalOf
    fun screeningTestHooks(): ScreeningTestHooks

    /** Debug builds add the QA export; release builds have no listeners. */
    @Multibinds
    fun screeningEventListeners(): Set<ScreeningEventListener>

    companion object {
        @Provides
        @Singleton
        fun phoneNumberNormalizer(): PhoneNumberNormalizer = PhoneNumberNormalizer()

        @Provides
        fun screeningConfig(): ScreeningConfig = ScreeningConfig()

        @Provides
        fun ruleValidator(
            normalizer: PhoneNumberNormalizer,
            config: ScreeningConfig,
        ): RuleValidator = RuleValidator(normalizer, TimeLimitedRegex(config.regexBudgetMillis))

        @Provides
        fun deviceSources(
            settings: UserSettingsDataSource,
            contacts: AndroidContactLookup,
            region: DeviceHomeRegionProvider,
            emergency: TelephonyEmergencyNumbers,
        ): DeviceSources = DeviceSources({ settings.preferences.first() }, contacts, region, emergency)

        @Provides
        fun listSources(
            rules: BlockRuleRepository,
            verified: LocalVerifiedBusinessLookup,
            spamList: SpamListRepository,
            reputation: ReputationRepository,
        ): ListSources =
            ListSources(
                rules = { rules.enabledCallRules().orThrow() },
                verifiedBusinesses = verified,
                spamList = { spamList.find(it).orThrow() },
                reputation = { reputation.findFresh(it).orThrow() },
                network = NoNetworkLookup,
            )

        @Provides
        @Singleton
        fun screeningEngine(
            device: DeviceSources,
            lists: ListSources,
            normalizer: PhoneNumberNormalizer,
            dispatchers: DispatcherProvider,
            config: ScreeningConfig,
        ): ScreeningEngine = ScreeningEngine(device, lists, normalizer, dispatchers, TimeSource.Monotonic, config)

        @Provides
        fun screeningLog(events: ScreeningEventRepository): ScreeningLog = ScreeningLog { events.record(it) }

        // A storage failure inside screening becomes an exception the engine turns into "allow" (reason error).
        private fun <T> AppResult<T>.orThrow(): T =
            when (this) {
                is AppResult.Success -> value
                is AppResult.Failure -> throw ScreeningSourceException(error)
            }
    }
}
