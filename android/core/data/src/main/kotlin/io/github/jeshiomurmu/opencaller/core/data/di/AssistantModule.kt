package io.github.jeshiomurmu.opencaller.core.data.di

import dagger.Binds
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantAvailability
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantCallNotices
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRepository
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomClient
import io.github.jeshiomurmu.opencaller.core.data.assistant.CallAssistant
import io.github.jeshiomurmu.opencaller.core.data.assistant.RoomScreenedCallStore
import io.github.jeshiomurmu.opencaller.core.data.assistant.ServerAssistantAvailability
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.network.AssistantApi
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import javax.inject.Singleton

/**
 * Phase 13: the call assistant (ADR 0010). [AssistantRoomClient] is optional: builds without LiveKit (F-Droid) have
 * none. [AssistantCallNotices] comes from `feature:assistant`.
 */
@Module
@InstallIn(SingletonComponent::class)
interface AssistantModule {
    /** The live screen's room connection, when the build has LiveKit. */
    @BindsOptionalOf
    fun assistantRoomClient(): AssistantRoomClient

    /** The "assistant answered a call" notification, when the build has the feature. */
    @BindsOptionalOf
    fun assistantCallNotices(): AssistantCallNotices

    /** Whether the assistant can be offered. */
    @Binds
    fun assistantAvailability(impl: ServerAssistantAvailability): AssistantAvailability

    /** Providers. */
    companion object {
        /** The Phase 13 assistant API. */
        @Provides
        fun assistantApi(network: OpenCallerNetwork): AssistantApi = network.assistantApi

        /** The number, the minutes and the history of screened calls. */
        @Provides
        @Singleton
        fun callAssistant(
            api: AssistantApi,
            access: DatabaseAccess,
            clock: Clock,
            dispatchers: DispatcherProvider,
        ): CallAssistant = AssistantRepository(api, RoomScreenedCallStore(access, dispatchers), clock)
    }
}
