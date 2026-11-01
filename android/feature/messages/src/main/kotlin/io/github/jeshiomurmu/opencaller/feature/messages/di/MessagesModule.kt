package io.github.jeshiomurmu.opencaller.feature.messages.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.MessageMetaRepository
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.ConversationSource
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.DeviceSmsStore
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessageAnalyzer
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessageMetaSink
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.MessagePipeline
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.SenderNames
import io.github.jeshiomurmu.opencaller.core.telephony.messaging.SenderResolver
import io.github.jeshiomurmu.opencaller.core.telephony.otp.OtpWatch
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.feature.messages.notify.ActiveThread
import io.github.jeshiomurmu.opencaller.feature.messages.notify.MessageNotifier
import io.github.jeshiomurmu.opencaller.feature.messages.ui.DeviceMessageSending
import io.github.jeshiomurmu.opencaller.feature.messages.ui.InboxCleaner
import io.github.jeshiomurmu.opencaller.feature.messages.ui.InboxCleanup
import io.github.jeshiomurmu.opencaller.feature.messages.ui.MessageSending
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadMetadata
import io.github.jeshiomurmu.opencaller.feature.messages.ui.ThreadPresence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The incoming-SMS pipeline and what the messaging screens use. */
@Module
@InstallIn(SingletonComponent::class)
internal interface MessagesModule {
    @Binds
    fun messageSending(impl: DeviceMessageSending): MessageSending

    @Binds
    fun inboxCleanup(impl: InboxCleaner): InboxCleanup

    companion object {
        // The contacts' name for an address; the registries and templates come after it (SenderResolver).
        @Provides
        fun senderNames(
            contacts: ContactLookup,
            normalizer: PhoneNumberNormalizer,
            region: HomeRegionProvider,
        ): SenderNames =
            SenderNames { address ->
                normalizer.fromHandle(address, region.homeRegion() ?: UNKNOWN_REGION)?.let { contacts.displayName(it) }
            }

        // One collaborator per pipeline step, as MessagePipeline takes them.
        @Suppress("LongParameterList")
        @Provides
        fun messagePipeline(
            inbox: DeviceSmsStore,
            resolver: SenderResolver,
            analyzer: MessageAnalyzer,
            otp: OtpWatch,
            meta: MessageMetaRepository,
            notifier: MessageNotifier,
            clock: Clock,
        ): MessagePipeline = MessagePipeline(inbox, resolver, analyzer, otp, MessageMetaSink { meta.save(it) }, notifier, clock)

        // A failed read shows the conversations without categories (all Personal), never no conversations.
        @Provides
        fun threadMetadata(meta: MessageMetaRepository): ThreadMetadata =
            object : ThreadMetadata {
                override fun observe(): Flow<List<MessageMeta>> = meta.observeAll().map { it.getOrNull().orEmpty() }

                override suspend fun forget(threadId: Long) {
                    meta.deleteThread(threadId)
                }
            }

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
