package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.Optional
import javax.inject.Inject
import javax.inject.Singleton

/** How screens start an app call without depending on `feature:voip`: an in-app intent with the number. */
object AppCallIntents {
    /** Action of the app-call screen (not exported; the intent must name this app's package). */
    const val ACTION_CALL = "io.github.jeshiomurmu.opencaller.action.APP_CALL"

    /** The number to call (E.164). */
    const val EXTRA_NUMBER = "io.github.jeshiomurmu.opencaller.extra.NUMBER"
}

/** What this phone can do to reach another OpenCaller user now. */
data class Reachability(
    val canCall: Boolean,
    val canFlash: Boolean,
)

/**
 * Whether app calls and Flash messages can be offered: the user is signed in, the server has them (`GET /v1/server`)
 * and, for calls, this build has them (F-Droid builds don't).
 */
@Singleton
class AppCallAvailability
    @Inject
    constructor(
        handler: Optional<AppCallHandler>,
        registrar: PushRegistrar,
        account: AccountStatus,
    ) {
        private val buildHasCalls = handler.isPresent

        /** What can be offered. */
        val reachability: Flow<Reachability> =
            combine(registrar.features, account.signedIn) { features, signedIn ->
                Reachability(canCall = signedIn && buildHasCalls && features.appCalls, canFlash = signedIn && features.flash)
            }
    }
