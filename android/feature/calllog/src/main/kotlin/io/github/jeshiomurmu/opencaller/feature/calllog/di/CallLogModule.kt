package io.github.jeshiomurmu.opencaller.feature.calllog.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.feature.calllog.NumberBlocker
import io.github.jeshiomurmu.opencaller.feature.calllog.RepositoryScreeningHistory
import io.github.jeshiomurmu.opencaller.feature.calllog.RuleNumberBlocker
import io.github.jeshiomurmu.opencaller.feature.calllog.ScreeningHistory

/** The call log's view of the screening log and the rules. */
@Module
@InstallIn(SingletonComponent::class)
internal interface CallLogModule {
    @Binds
    fun screeningHistory(impl: RepositoryScreeningHistory): ScreeningHistory

    @Binds
    fun numberBlocker(impl: RuleNumberBlocker): NumberBlocker
}
