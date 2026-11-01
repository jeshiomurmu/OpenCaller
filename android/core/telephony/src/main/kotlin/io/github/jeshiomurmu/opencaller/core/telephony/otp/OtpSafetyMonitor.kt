package io.github.jeshiomurmu.opencaller.core.telephony.otp

import io.github.jeshiomurmu.opencaller.core.telephony.incall.CallStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts what the OTP guard watches (docs/architecture.md §4.5): the in-call service's calls, the phone's call state
 * in screening-only mode, and ended calls for flash-call detection. Started once from the application.
 */
@Singleton
class OtpSafetyMonitor
    @Inject
    constructor(
        private val inCall: CallStateRepository,
        private val inCallFeed: InCallFeed,
        private val phoneState: PhoneStateFeed,
        private val tracker: CallActivityTracker,
        private val watch: OtpWatch,
    ) {
        /** Starts watching in [scope]; [phoneState] is registered on the calling (main) thread. */
        fun start(scope: CoroutineScope) {
            phoneState.start()
            scope.launch { inCall.state.collect { inCallFeed.apply(it) } }
            scope.launch { tracker.ended.collect { watch.callEnded(it) } }
        }

        /** Starts the phone-state listener again (for example after `READ_PHONE_STATE` was granted). */
        fun refresh() = phoneState.start()
    }
