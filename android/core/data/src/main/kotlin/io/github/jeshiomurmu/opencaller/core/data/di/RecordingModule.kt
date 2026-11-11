package io.github.jeshiomurmu.opencaller.core.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.recording.CallRecordings
import io.github.jeshiomurmu.opencaller.core.data.recording.RecordingDownloads
import io.github.jeshiomurmu.opencaller.core.data.recording.RecordingRepository
import io.github.jeshiomurmu.opencaller.core.data.recording.RoomRecordingStore
import io.github.jeshiomurmu.opencaller.core.data.recording.WorkManagerRecordingDownloads
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.RecordingApi
import java.io.File
import javax.inject.Singleton

/** Phase 13: the recording line (ADR 0009). */
@Module
@InstallIn(SingletonComponent::class)
interface RecordingModule {
    /** Background downloads of finished recordings. */
    @Binds
    fun recordingDownloads(impl: WorkManagerRecordingDownloads): RecordingDownloads

    /** Providers. */
    companion object {
        /** The Phase 13 API. */
        @Provides
        fun recordingApi(network: OpenCallerNetwork): RecordingApi = network.recordingApi

        /** Recordings, saved in the app's private files (not in backups' cache, never shared with other apps). */
        @Provides
        @Singleton
        fun callRecordings(
            @ApplicationContext context: Context,
            api: RecordingApi,
            access: DatabaseAccess,
            clock: Clock,
            dispatchers: DispatcherProvider,
        ): CallRecordings =
            RecordingRepository(
                api = api,
                store = RoomRecordingStore(access, dispatchers),
                folder = File(context.filesDir, RecordingRepository.FOLDER),
                clock = clock,
                dispatchers = dispatchers,
            )
    }
}
