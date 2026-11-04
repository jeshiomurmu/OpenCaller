package io.github.jeshiomurmu.opencaller.push

import android.app.Activity
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.data.voip.NotificationRouter
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import io.github.jeshiomurmu.opencaller.core.model.ServerFeatures
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** The build's push provider: FCM (play) or a UnifiedPush distributor (foss). */
interface PushSetup {
    /** Gets this device's push endpoint from the provider (which hands it to [PushRegistrar]); [activity] may pick a distributor. */
    fun start(
        features: ServerFeatures,
        activity: Activity?,
    )
}

/**
 * Push for app calls and Flash messages (ADR 0007): while the user is signed in and the server offers them, the
 * build's provider registers this device, the endpoint is sent to the server, and items whose push never arrived
 * are fetched when the app starts or comes to the foreground.
 */
@Singleton
class VoipStartup
    @Inject
    constructor(
        private val account: AccountStatus,
        private val registrar: PushRegistrar,
        private val router: NotificationRouter,
        private val push: PushSetup,
        dispatchers: DispatcherProvider,
    ) {
        /** Work that must outlive the push service that started it (a fetch after a push). */
        val scope = CoroutineScope(SupervisorJob() + dispatchers.io + CoroutineExceptionHandler { _, e -> Timber.w(e, "Push work failed") })

        /** Called once at app start: after every sign-in, the features, the endpoint and missed items. */
        fun start() {
            if (!account.serverConfigured) return
            scope.launch {
                account.signedIn
                    .distinctUntilChanged()
                    .filter { it }
                    .collect { refresh(activity = null) }
            }
        }

        /** The app came to the foreground ([activity] lets a UnifiedPush distributor be picked). */
        fun onForeground(activity: Activity) {
            if (!account.serverConfigured) return
            scope.launch { if (account.signedIn.first()) refresh(activity) }
        }

        private suspend fun refresh(activity: Activity?) {
            val features = registrar.refreshFeatures() ?: registrar.features.first()
            if (!features.flash) return
            push.start(features, activity)
            registrar.resend()
            router.catchUp()
        }
    }
