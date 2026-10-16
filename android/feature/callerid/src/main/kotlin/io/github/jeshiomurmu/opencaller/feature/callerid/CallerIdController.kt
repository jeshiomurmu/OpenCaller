package io.github.jeshiomurmu.opencaller.feature.callerid

import android.os.Handler
import android.os.Looper
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.AfterCallSurface
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.telephony.AccessStatusSource
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdPresenter
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningResult
import io.github.jeshiomurmu.opencaller.core.telephony.screening.wantsAfterCall
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Where caller ID can appear, and the watch for the end of the call. */
class CallerIdSurfaces
    @Inject
    constructor(
        val overlay: CallerCardOverlay,
        val notifications: CallerIdNotifications,
        val callEnd: CallEndWatcher,
    )

/**
 * Shows caller ID for a call that rings while OpenCaller is not the default phone app (docs/architecture.md §4.1):
 * the caller card over other apps when the user allowed it, otherwise a heads-up notification. When the call ends,
 * the card closes and, for numbers that aren't contacts, the after-call summary appears (§4.3). As the default phone
 * app (Phase 6) the in-call screen shows the card instead.
 */
@Singleton
class CallerIdController
    @Inject
    constructor(
        private val access: AccessStatusSource,
        private val settings: UserSettingsDataSource,
        private val formatter: CallerCardFormatter,
        private val surfaces: CallerIdSurfaces,
        private val listeners: Set<@JvmSuppressWildcards CallerIdListener>,
        dispatchers: DispatcherProvider,
    ) : CallerIdPresenter {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> Timber.e(e, "Caller ID failed") })
        private val handler = Handler(Looper.getMainLooper())
        private var current: Showing? = null

        override fun present(result: ScreeningResult) {
            scope.launch {
                access.refresh()
                val status = access.status.value
                val preferred = status.callerIdSurface(settings.preferences.first().overlayEnabled)
                val card = formatter.card(result.caller)
                end(callEnded = false)
                val surface = show(card, preferred) ?: return@launch
                listeners.forEach { it.onCallerIdShown(result, surface) }
                val afterCall = result.wantsAfterCall && status.afterCallSurface == AfterCallSurface.NOTIFICATION
                val showing = Showing(card, afterCall)
                current = showing
                showing.watch = surfaces.callEnd.watch { if (current === showing) end(callEnded = true) }
                handler.postDelayed(
                    showing.timeout,
                    if (surfaces.callEnd.canObserveEveryCall()) MAX_CARD_MILLIS else UNOBSERVED_CARD_MILLIS,
                )
            }
        }

        // The overlay when allowed (falling back to the notification if the window is refused), else the notification.
        private fun show(
            card: CallerCardModel,
            preferred: CallerIdSurface,
        ): CallerIdSurface? =
            when {
                preferred == CallerIdSurface.IN_CALL_SCREEN -> null
                preferred == CallerIdSurface.OVERLAY && surfaces.overlay.show(card) { end(callEnded = false) } -> CallerIdSurface.OVERLAY
                surfaces.notifications.showCallerId(card) -> CallerIdSurface.NOTIFICATION
                else -> null
            }

        private fun end(callEnded: Boolean) {
            val showing = current ?: return
            current = null
            showing.watch?.stop()
            handler.removeCallbacks(showing.timeout)
            surfaces.overlay.dismiss()
            surfaces.notifications.cancelCallerId()
            if (callEnded && showing.afterCall) surfaces.notifications.showAfterCall(showing.card)
        }

        private inner class Showing(
            val card: CallerCardModel,
            val afterCall: Boolean,
        ) {
            var watch: CallWatch? = null

            // Safety net if the end of the call is never observed (e.g. a silenced call without call-state access).
            val timeout = Runnable { if (current === this) end(callEnded = false) }
        }

        private companion object {
            const val MAX_CARD_MILLIS = 10 * 60 * 1000L

            // Without call-state access a silenced call's end can't be seen: close the card after a ring's length.
            const val UNOBSERVED_CARD_MILLIS = 90 * 1000L
        }
    }
