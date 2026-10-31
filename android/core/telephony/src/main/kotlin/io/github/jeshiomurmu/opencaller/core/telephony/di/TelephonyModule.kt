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
import io.github.jeshiomurmu.opencaller.core.data.sync.CountrySource
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AppRole
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.CallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceAccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceCallPlacer
import io.github.jeshiomurmu.opencaller.core.telephony.DeviceSimAccounts
import io.github.jeshiomurmu.opencaller.core.telephony.QuickReplyTemplates
import io.github.jeshiomurmu.opencaller.core.telephony.RoleManagerHelper
import io.github.jeshiomurmu.opencaller.core.telephony.SimAccounts
import io.github.jeshiomurmu.opencaller.core.telephony.lookup.ServerNetworkLookup
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DefaultSmsAppRole
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceConversations
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceMessagingSims
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagingSimSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.CallLogSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.DefaultPhoneAppRole
import io.github.jeshiomurmu.opencaller.core.telephony.providers.DeviceCallLog
import io.github.jeshiomurmu.opencaller.core.telephony.providers.DeviceContacts
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.DeviceSources
import io.github.jeshiomurmu.opencaller.core.telephony.screening.EmergencyNumbers
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ListSources
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Singleton
import kotlin.time.TimeSource

/** Binds the telephony interfaces to their device implementations. */
@Suppress("TooManyFunctions") // One binding per telephony interface, as Hilt wants them.
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

    /** Debug builds add the QA export; release builds have no listeners. */
    @Multibinds
    fun callerIdListeners(): Set<CallerIdListener>

    @Binds
    fun homeRegionProvider(impl: DeviceHomeRegionProvider): HomeRegionProvider

    /** The spam list is the one of the user's country (SIM, then network, then locale). */
    @Binds
    fun countrySource(impl: DeviceHomeRegionProvider): CountrySource

    @Binds
    fun contactLookup(impl: AndroidContactLookup): ContactLookup

    @Binds
    fun callPlacer(impl: DeviceCallPlacer): CallPlacer

    @Binds
    fun simAccounts(impl: DeviceSimAccounts): SimAccounts

    @Binds
    fun callLog(impl: DeviceCallLog): CallLogSource

    @Binds
    fun contacts(impl: DeviceContacts): ContactsSource

    @Binds
    fun emergencyNumbers(impl: TelephonyEmergencyNumbers): EmergencyNumbers

    @Binds
    fun messagingSims(impl: DeviceMessagingSims): MessagingSimSource

    @Binds
    fun conversations(impl: DeviceConversations): ConversationSource

    @Suppress("TooManyFunctions") // One provider per object Hilt can't construct itself.
    companion object {
        @Provides
        @Singleton
        fun phoneNumberNormalizer(): PhoneNumberNormalizer = PhoneNumberNormalizer()

        @Provides
        fun screeningConfig(): ScreeningConfig = ScreeningConfig()

        @Provides
        fun quickReplyTemplates(settings: UserSettingsDataSource): QuickReplyTemplates =
            QuickReplyTemplates { settings.preferences.map { it.quickReplies }.distinctUntilChanged() }

        @Provides
        fun defaultPhoneAppRole(roles: RoleManagerHelper): DefaultPhoneAppRole = DefaultPhoneAppRole { roles.isRoleHeld(AppRole.DIALER) }

        @Provides
        fun defaultSmsAppRole(roles: RoleManagerHelper): DefaultSmsAppRole = DefaultSmsAppRole { roles.isRoleHeld(AppRole.SMS) }

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
            network: ServerNetworkLookup,
        ): ListSources =
            ListSources(
                rules = { rules.enabledCallRules().orThrow() },
                verifiedBusinesses = verified,
                spamList = { spamList.find(it).orThrow() },
                reputation = { reputation.findFresh(it).orThrow() },
                network = network,
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
        fun callerLookup(
            device: DeviceSources,
            lists: ListSources,
            normalizer: PhoneNumberNormalizer,
            dispatchers: DispatcherProvider,
            config: ScreeningConfig,
        ): CallerLookup = CallerLookup(device, lists, normalizer, dispatchers, config)

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
