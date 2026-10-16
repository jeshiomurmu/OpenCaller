package io.github.jeshiomurmu.opencaller.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DefaultDispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.SystemWallClock
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File
import javax.inject.Singleton

/** App-wide bindings for the pure-Kotlin core modules (which have no DI of their own). */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Production coroutine dispatchers. */
    @Provides
    @Singleton
    fun dispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider

    /** Wall clock. */
    @Provides
    @Singleton
    fun clock(): Clock = SystemWallClock

    /** User settings (one DataStore per file per process). */
    @Provides
    @Singleton
    fun userSettings(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): UserSettingsDataSource =
        UserSettingsDataSource.create(scope = CoroutineScope(dispatchers.io + SupervisorJob())) {
            File(context.filesDir, "datastore/user_settings.pb")
        }
}
