package io.github.jeshiomurmu.opencaller

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.SpamListSeeder
import io.github.jeshiomurmu.opencaller.logging.RedactingTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Application entry point; hosts the Hilt component graph and installs the redacting logger. At start it loads the
 * bundled seed spam list, which also opens the encrypted database early, so the first screened call doesn't pay for
 * the key derivation.
 */
@HiltAndroidApp
class OpenCallerApplication : Application() {
    @Inject
    lateinit var spamListSeeder: SpamListSeeder

    @Inject
    lateinit var dispatchers: DispatcherProvider

    override fun onCreate() {
        super.onCreate()
        // Release builds log nothing until opt-in crash reporting exists (docs/architecture.md §10).
        if (BuildConfig.DEBUG) Timber.plant(RedactingTree())
        CoroutineScope(SupervisorJob() + dispatchers.io).launch {
            when (val seeded = spamListSeeder.seedIfEmpty()) {
                is AppResult.Success -> Timber.i("Seed spam list: %d entries added", seeded.value)
                is AppResult.Failure -> Timber.w("Seed spam list not loaded: %s", seeded.error)
            }
        }
    }
}
