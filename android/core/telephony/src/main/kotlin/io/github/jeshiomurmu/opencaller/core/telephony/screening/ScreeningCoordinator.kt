package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.telephony.incall.ScreenedCalls
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Shows caller ID for a screened call: the overlay card or a heads-up notification (`feature:callerid`). */
fun interface CallerIdPresenter {
    /** Called on the main thread once [result] has been sent to Android, for calls that ring or are silenced. */
    fun present(result: ScreeningResult)
}

/** Told when caller ID was shown for a call, and where (the debug QA export uses this). */
fun interface CallerIdListener {
    /** Caller ID for [result] is showing on [surface]. */
    fun onCallerIdShown(
        result: ScreeningResult,
        surface: CallerIdSurface,
    )
}

/** Where screening decisions are logged (the `screening_event` table). */
fun interface ScreeningLog {
    /** Saves [event] and returns its id. */
    suspend fun record(event: ScreeningEvent): AppResult<Long>
}

/** Told about every logged screening decision (the debug QA export uses this). */
fun interface ScreeningEventListener {
    /** [event] was saved; [result] is the engine's full answer. */
    fun onScreened(
        event: ScreeningEvent,
        result: ScreeningResult,
    )
}

/**
 * What happens with a screening answer besides the answer itself: the in-memory copy for the default phone app's
 * in-call screen ([screened]), the log, the listeners and caller ID.
 */
class ScreeningFollowUps
    @Inject
    constructor(
        val screened: ScreenedCalls,
        val log: ScreeningLog,
        val listeners: Set<@JvmSuppressWildcards ScreeningEventListener>,
        val presenter: CallerIdPresenter,
    )

/**
 * Runs one screening from [OpenCallerScreeningService]: decides with [ScreeningEngine], answers Android at once,
 * then (off the critical path) logs the decision and shows caller ID. Logging and the card outlive the service,
 * which Android unbinds as soon as it has the answer.
 */
@Singleton
class ScreeningCoordinator
    @Inject
    constructor(
        private val engine: ScreeningEngine,
        private val followUps: ScreeningFollowUps,
        private val clock: Clock,
        private val dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(
                SupervisorJob() + dispatchers.main +
                    CoroutineExceptionHandler { _, e -> Timber.e(e, "Screening follow-up failed") },
            )

        /**
         * Screens [call] and calls [respond] exactly once with the decision. [respond] runs on the main thread;
         * if anything here fails, the call is allowed (fail open).
         */
        fun screen(
            call: ScreeningCall,
            respond: (Decision) -> Unit,
        ) {
            scope.launch {
                val result = engine.screen(call)
                // In memory only, for the in-call screen of the default phone app (Telecom adds the call next).
                if (result.showsCallerId) followUps.screened.remember(result)
                respondSafely(respond, result.decision)
                record(result)
                if (result.showsCallerId) present(result)
            }
        }

        // Android may already have given up on the call (5 s limit); that must not stop the logging.
        @Suppress("TooGenericExceptionCaught")
        private fun respondSafely(
            respond: (Decision) -> Unit,
            decision: Decision,
        ) {
            try {
                respond(decision)
            } catch (e: RuntimeException) {
                Timber.w(e, "Could not answer the call screening request")
            }
        }

        private suspend fun record(result: ScreeningResult) {
            val event = result.toEvent(clock.nowMillis())
            when (val saved = followUps.log.record(event)) {
                is AppResult.Success -> followUps.listeners.forEach { it.onScreened(event.copy(id = saved.value), result) }
                is AppResult.Failure -> Timber.w("Screening event not saved: %s", saved.error)
            }
        }

        // The card is a nice-to-have: a failure to show it must never affect the call.
        @Suppress("TooGenericExceptionCaught")
        private fun present(result: ScreeningResult) {
            try {
                followUps.presenter.present(result)
            } catch (e: RuntimeException) {
                Timber.w(e, "Caller ID could not be shown")
            }
        }
    }

/**
 * True when a summary should follow the call: an incoming call that rang (or was silenced) from a number that isn't
 * a contact. Blocked calls go to the blocked-calls log instead.
 */
val ScreeningResult.wantsAfterCall: Boolean
    get() = showsCallerId && caller.number != null && reason != ScreeningReason.CONTACT

/** The log entry for [this] result; emergency calls are logged without their number. */
fun ScreeningResult.toEvent(atMillis: Long): ScreeningEvent =
    ScreeningEvent(
        id = 0,
        number = caller.number.takeIf { reason != ScreeningReason.EMERGENCY },
        direction = direction,
        decision = decision,
        reason = reason,
        spamScore = caller.spamVerdict?.score,
        verification = caller.verification,
        latencyMs = latencyMs,
        atMillis = atMillis,
    )
