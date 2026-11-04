package io.github.jeshiomurmu.opencaller.feature.callerid.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.telephony.NotificationListenerAccess
import io.github.jeshiomurmu.opencaller.core.telephony.incall.AfterCallPresenter
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdPresenter
import io.github.jeshiomurmu.opencaller.feature.callerid.AfterCallActions
import io.github.jeshiomurmu.opencaller.feature.callerid.AfterCallScreens
import io.github.jeshiomurmu.opencaller.feature.callerid.CallEndWatcher
import io.github.jeshiomurmu.opencaller.feature.callerid.CallerIdController
import io.github.jeshiomurmu.opencaller.feature.callerid.DeviceCallEndWatcher
import io.github.jeshiomurmu.opencaller.feature.callerid.RepositoryAfterCallActions
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.AudioConditionsReader
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.DeviceAudioConditions
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.Speaker
import io.github.jeshiomurmu.opencaller.feature.callerid.announce.TextToSpeechSpeaker
import io.github.jeshiomurmu.opencaller.feature.callerid.messenger.DeviceNotificationListenerAccess

/** Caller ID for screened calls, and the after-call card. */
@Module
@InstallIn(SingletonComponent::class)
internal interface CallerIdModule {
    @Binds
    fun callerIdPresenter(impl: CallerIdController): CallerIdPresenter

    @Binds
    fun callEndWatcher(impl: DeviceCallEndWatcher): CallEndWatcher

    @Binds
    fun afterCallActions(impl: RepositoryAfterCallActions): AfterCallActions

    @Binds
    fun afterCallPresenter(impl: AfterCallScreens): AfterCallPresenter

    @Binds
    fun audioConditions(impl: DeviceAudioConditions): AudioConditionsReader

    @Binds
    fun speaker(impl: TextToSpeechSpeaker): Speaker

    @Binds
    fun notificationListenerAccess(impl: DeviceNotificationListenerAccess): NotificationListenerAccess
}
