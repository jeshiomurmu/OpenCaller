package io.github.jeshiomurmu.opencaller.core.data.di

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.voip.AppCallHandler
import io.github.jeshiomurmu.opencaller.core.data.voip.CallerClips
import io.github.jeshiomurmu.opencaller.core.data.voip.ClipNetwork
import io.github.jeshiomurmu.opencaller.core.data.voip.ClipPrefetch
import io.github.jeshiomurmu.opencaller.core.data.voip.VideoClips
import io.github.jeshiomurmu.opencaller.core.data.voip.VoipPreferences
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerNetwork
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import java.io.File
import javax.inject.Singleton

/**
 * Phase 11: push, app calls, Flash messages and video caller ID. [AppCallHandler] is optional: builds without
 * `feature:voip` (F-Droid) have none.
 */
@Module
@InstallIn(SingletonComponent::class)
interface VoipModule {
    /** App calls, when the build has them. */
    @BindsOptionalOf
    fun appCallHandler(): AppCallHandler

    /** Providers. */
    companion object {
        /** The Phase 11 API. */
        @Provides
        fun voipApi(network: OpenCallerNetwork): VoipApi = network.voipApi

        /** Push endpoint, server features, clip-download setting. */
        @Provides
        @Singleton
        fun voipPreferences(
            @ApplicationContext context: Context,
        ): VoipPreferences = VoipPreferences(PreferenceDataStoreFactory.create { context.preferencesDataStoreFile(VoipPreferences.FILE_NAME) })

        /** The clip cache (the cache directory: Android may clear it, and backups leave it out). */
        @Provides
        @Singleton
        @Suppress("LongParameterList") // Hilt hands the cache everything it needs.
        fun videoClips(
            @ApplicationContext context: Context,
            api: VoipApi,
            preferences: VoipPreferences,
            clock: Clock,
            access: DatabaseAccess,
            dispatchers: DispatcherProvider,
        ): VideoClips = VideoClips(File(context.cacheDir, "video-clips"), api, preferences, networkOf(context), clock, access, dispatchers)

        /** Lookups prefetch clips into the cache. */
        @Provides
        fun clipPrefetch(clips: VideoClips): ClipPrefetch = clips

        /** The in-call screens read clips from the cache. */
        @Provides
        fun callerClips(clips: VideoClips): CallerClips = clips

        private fun networkOf(context: Context): ClipNetwork {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            return ClipNetwork { mobileDataAllowed ->
                val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                when {
                    capabilities == null -> false
                    mobileDataAllowed -> true
                    else -> capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }
        }
    }
}
