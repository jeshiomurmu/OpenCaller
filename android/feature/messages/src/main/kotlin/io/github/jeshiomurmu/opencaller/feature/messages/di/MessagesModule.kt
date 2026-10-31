package io.github.jeshiomurmu.opencaller.feature.messages.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.data.MessageMetaRepository
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceSmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessageMetaSink
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagePipeline
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.SenderNames
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ActiveThread
import io.github.jeshiomurmu.opencaller.feature.messages.notify.MessageNotifier
import io.github.jeshiomurmu.opencaller.feature.messages.ui.DeviceMessageSending
import io.github.jeshiomurmu.opencaller.feature.messages.ui.MessageSending
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadMetaCleaner
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadPresence

/** The incoming-SMS pipeline and what the messaging screens use. */
@Module
@InstallIn(SingletonComponent::class)
internal interface MessagesModule {
    @Binds
    fun messageSending(impl: DeviceMessageSending): MessageSending

    companion object {
        // A sender's name comes from the user's contacts only (sender IDs and the registry are Phase 7b).
        @Provides
        fun senderNames(
            contacts: ContactLookup,
            normalizer: PhoneNumberNormalizer,
            region: HomeRegionProvider,
        ): SenderNames =
            SenderNames { address ->
                normalizer.fromHandle(address, region.homeRegion() ?: UNKNOWN_REGION)?.let { contacts.displayName(it) }
            }

        @Provides
        fun messagePipeline(
            inbox: DeviceSmsStore,
            names: SenderNames,
            meta: MessageMetaRepository,
            notifier: MessageNotifier,
            clock: Clock,
        ): MessagePipeline = MessagePipeline(inbox, names, MessageMetaSink { meta.save(it) }, notifier, clock)

        @Provides
        fun threadMetaCleaner(meta: MessageMetaRepository): ThreadMetaCleaner = ThreadMetaCleaner { meta.deleteThread(it) }

        @Provides
        fun threadPresence(
            active: ActiveThread,
            conversations: ConversationSource,
            notifier: MessageNotifier,
        ): ThreadPresence =
            object : ThreadPresence {
                override suspend fun shown(threadId: Long) {
                    active.set(threadId)
                    conversations.markRead(threadId)
                    notifier.cancel(threadId)
                }

                override fun hidden() = active.set(null)
            }

        private const val UNKNOWN_REGION = "ZZ"
    }
}
