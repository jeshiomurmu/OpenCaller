package io.github.jeshiomurmu.opencaller

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.github.jeshiomurmu.opencaller.logging.RedactingTree
import timber.log.Timber

/** Application entry point; hosts the Hilt component graph and installs the redacting logger. */
@HiltAndroidApp
class OpenCallerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Release builds log nothing until opt-in crash reporting exists (docs/architecture.md §10).
        if (BuildConfig.DEBUG) Timber.plant(RedactingTree())
    }
}
