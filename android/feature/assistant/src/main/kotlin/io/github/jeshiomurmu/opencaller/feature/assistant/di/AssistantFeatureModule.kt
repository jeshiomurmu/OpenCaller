package io.github.jeshiomurmu.opencaller.feature.assistant.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.data.assistant.AssistantCallNotices
import io.github.jeshiomurmu.opencaller.feature.assistant.AssistantCallNotifier

/** The "assistant is answering a call" notification. */
@Module
@InstallIn(SingletonComponent::class)
internal interface AssistantFeatureModule {
    @Binds
    fun assistantCallNotices(impl: AssistantCallNotifier): AssistantCallNotices
}
