package io.github.jeshiomurmu.opencaller

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.SpamListSeeder
import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.network.NetworkLog
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningWarmUp
import io.github.jeshiomurmu.opencaller.logging.RedactingTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point; hosts the Hilt component graph and installs the redacting logger. At start, in the
 * background, it loads the bundled seed spam list and warms up call screening (database, numbering metadata of the
 * user's country), so the first screened call is as fast as the others. With a server configured it keeps the daily
 * spam-list update scheduled (unmetered networks unless the user allows mobile data) and sends queued reports.
 * WorkManager is started on demand with Hilt's worker factory (its default initializer is removed in the manifest).
 */
@HiltAndroidApp
class OpenCallerApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var spamListSeeder: SpamListSeeder

    @Inject
    lateinit var screeningWarmUp: ScreeningWarmUp

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var work: BackgroundWork

    @Inject
    lateinit var settings: UserSettingsDataSource

    @Inject
    lateinit var server: ServerConfig

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // Release builds log nothing until opt-in crash reporting exists (docs/architecture.md §10).
        if (BuildConfig.DEBUG) Timber.plant(RedactingTree())
        NetworkLog.sink = { message, error -> Timber.w(error, message) }
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
        scope.launch {
            when (val seeded = spamListSeeder.seedIfEmpty()) {
                is AppResult.Success -> Timber.i("Seed spam list: %d entries added", seeded.value)
                is AppResult.Failure -> Timber.w("Seed spam list not loaded: %s", seeded.error)
            }
            screeningWarmUp.warmUp()
        }
        if (server.isConfigured) {
            work.uploadReports()
            scope.launch {
                settings.preferences
                    .map { it.listUpdatesOnMobileData }
                    .distinctUntilChanged()
                    .collect(work::scheduleListSync)
            }
        }
    }
}
