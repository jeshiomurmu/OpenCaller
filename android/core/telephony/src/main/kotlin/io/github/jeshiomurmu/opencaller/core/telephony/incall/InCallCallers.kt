package io.github.jeshiomurmu.opencaller.core.telephony.incall

import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningResult
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The latest screening answers for incoming calls, kept in memory for a few minutes so the in-call screen shows the
 * same caller card as caller ID (it includes the live lookup, which the in-call screen never repeats). Nothing is
 * written anywhere.
 */
@Singleton
class ScreenedCalls
    @Inject
    constructor(
        private val clock: Clock,
    ) {
        private val results = ConcurrentHashMap<PhoneNumber, Remembered>()

        @Volatile private var last: Remembered? = null

        /**
         * Keeps [result]: the newest of any call (the OTP guard's view of who the user talks to), and by number the
         * incoming calls that ring (the in-call screen's caller card; blocked calls never reach it).
         */
        fun remember(result: ScreeningResult) {
            val now = clock.nowMillis()
            last = Remembered(result, now)
            val number = result.caller.number
            if (number == null || result.direction != Direction.INCOMING || !result.showsCallerId) return
            results.entries.removeIf { now - it.value.atMillis > KEEP_MILLIS }
            results[number] = Remembered(result, now)
        }

        /** The answer for [number] from the last few minutes, if any. */
        fun latest(number: PhoneNumber): ScreeningResult? =
            results[number]?.takeIf { clock.nowMillis() - it.atMillis <= KEEP_MILLIS }?.result

        /** The newest screening answer (any direction), if it is at most [withinMillis] old. */
        fun newest(withinMillis: Long): ScreeningResult? = last?.takeIf { clock.nowMillis() - it.atMillis <= withinMillis }?.result

        private class Remembered(
            val result: ScreeningResult,
            val atMillis: Long,
        )

        private companion object {
            // Long enough for a call to ring out; screening always runs right before Telecom adds the call.
            const val KEEP_MILLIS = 5 * 60 * 1000L
        }
    }

/** Who is on the other end of a call, for the in-call screen. */
@Singleton
class InCallCallers
    @Inject
    constructor(
        private val lookup: CallerLookup,
        private val screened: ScreenedCalls,
    ) {
        /**
         * The caller card for [call]: the screening answer when the call was screened, otherwise what the device knows
         * (contacts, spam list, reputation cache; never the network). A hidden caller or an emergency number has no
         * number.
         */
        suspend fun callerFor(call: TrackedCall): CallerInfo {
            val described = if (call.hidden) CallerInfo(number = null) else lookup.describe(call.handle)
            val screenedCaller =
                described.number
                    ?.let(screened::latest)
                    ?.caller
                    ?.takeIf { call.direction != Direction.OUTGOING }
            return screenedCaller ?: described
        }
    }
