package io.github.jeshiomurmu.opencaller.feature.recording.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallRecorder
import io.github.jeshiomurmu.opencaller.feature.recording.LineRecorder
import io.github.jeshiomurmu.opencaller.feature.recording.MediaRecordingPlayer
import io.github.jeshiomurmu.opencaller.feature.recording.RecordingPlayer

/** The recording line for the in-call screen, and playback. */
@Module
@InstallIn(SingletonComponent::class)
internal interface RecordingFeatureModule {
    @Binds
    fun callRecorder(impl: LineRecorder): CallRecorder

    @Binds
    fun recordingPlayer(impl: MediaRecordingPlayer): RecordingPlayer
}
