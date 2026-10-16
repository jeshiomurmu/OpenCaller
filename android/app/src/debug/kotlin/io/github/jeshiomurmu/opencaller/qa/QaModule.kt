package io.github.jeshiomurmu.opencaller.qa

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEventListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningTestHooks

/** Debug builds only: QA hooks and the QA export (release builds bind neither). */
@Module
@InstallIn(SingletonComponent::class)
interface QaModule {
    @Binds
    fun screeningTestHooks(impl: QaHooks): ScreeningTestHooks

    @Binds
    @IntoSet
    fun screeningExport(impl: QaExport): ScreeningEventListener

    @Binds
    @IntoSet
    fun callerIdExport(impl: QaExport): CallerIdListener
}
