package io.github.jeshiomurmu.opencaller.feature.voip.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantRoomClient
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallHandler
import io.github.jeshiomurmu.opencaller.feature.voip.AppCallController
import io.github.jeshiomurmu.opencaller.feature.voip.LiveKitAssistantRoom

/** This build has app calls (pushes for calls go to the controller) and LiveKit for the assistant's live screen. */
@Module
@InstallIn(SingletonComponent::class)
interface VoipCallModule {
    /** The controller handles call pushes. */
    @Binds
    fun appCallHandler(impl: AppCallController): AppCallHandler

    /** The assistant's live screen joins its call's room through LiveKit. */
    @Binds
    fun assistantRoomClient(impl: LiveKitAssistantRoom): AssistantRoomClient
}
