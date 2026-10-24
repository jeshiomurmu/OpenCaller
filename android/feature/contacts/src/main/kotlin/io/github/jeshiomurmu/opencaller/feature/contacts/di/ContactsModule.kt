package io.github.jeshiomurmu.opencaller.feature.contacts.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.feature.contacts.LookupNumberInsights
import io.github.jeshiomurmu.opencaller.feature.contacts.NumberInsights

/** What the contact detail screen shows about a contact's numbers. */
@Module
@InstallIn(SingletonComponent::class)
internal interface ContactsModule {
    @Binds
    fun numberInsights(impl: LookupNumberInsights): NumberInsights
}
