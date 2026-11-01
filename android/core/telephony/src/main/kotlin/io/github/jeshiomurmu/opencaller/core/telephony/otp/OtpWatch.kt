package io.github.jeshiomurmu.opencaller.core.telephony.otp

import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import javax.inject.Inject
import javax.inject.Singleton

/** The guard's warnings on screen (`feature:otp`). Called off the main thread. */
interface OtpAlarm {
    /**
     * A one-time code arrived during, or just after, a call with a stranger (TC-OTP-005): the full-screen warning.
     * [codeFrom] names who sent the code when known; [flashCall] is true when the code came as a missed call.
     */
    fun warnDuringCall(
        codeFrom: String?,
        flashCall: Boolean,
    )

    /** [codes] one-time codes from different senders arrived within minutes (TC-OTP-009): an alert. */
    fun warnBurst(codes: Int)
}

/** One-time codes received recently, as (time, sender key) pairs, from OpenCaller's message metadata. */
fun interface RecentOtps {
    /** The codes received at or after [atMillis]. */
    suspend fun since(atMillis: Long): List<Pair<Long, String>>
}

/** Flash-call verification ranges on the phone (`core:data` registry). */
fun interface VerificationRanges {
    /** The provider whose range [number] is in, or `null`. */
    suspend fun providerOf(number: PhoneNumber): String?
}

/**
 * The OTP safety guard and burst alert (docs/architecture.md §4.5, PRD F-PR-11/12): a code arriving by SMS or as a
 * flash call while, or up to 2 minutes after, the user talks to a stranger raises the full-screen warning; three or
 * more codes from two or more senders in 10 minutes raise the burst alert. Everything stays on the device.
 */
@Singleton
class OtpWatch
    @Inject
    constructor(
        private val calls: CallActivityTracker,
        private val recent: RecentOtps,
        private val ranges: VerificationRanges,
        private val alarm: OtpAlarm,
        private val clock: Clock,
    ) {
        /**
         * A code arrived by SMS from [senderKey] (the conversation; [senderName] when known). Warns as needed and
         * returns the risks to store with the message.
         */
        suspend fun received(
            senderKey: String,
            senderName: String?,
        ): Set<MessageRisk> {
            val now = clock.nowMillis()
            val duringCall = OtpRules.riskyCall(calls.recent(), now) != null
            if (duringCall) alarm.warnDuringCall(senderName, flashCall = false)
            val codes = recent.since(now - OtpRules.BURST_WINDOW_MILLIS) + (now to senderKey)
            val burst = OtpRules.isBurst(codes, now)
            if (burst) alarm.warnBurst(codes.size)
            return buildSet {
                if (duringCall) add(MessageRisk.OTP_DURING_CALL)
                if (burst) add(MessageRisk.OTP_BURST)
            }
        }

        /** A call ended: a flash call from a verification range during another call with a stranger warns too (TC-OTP-007). */
        suspend fun callEnded(call: ObservedCall) {
            val number = call.number
            if (number == null || !OtpRules.isFlashCall(call)) return
            val provider = ranges.providerOf(number)
            if (provider != null && OtpRules.riskyCall(calls.recent(), clock.nowMillis()) != null) {
                alarm.warnDuringCall(provider, flashCall = true)
            }
        }
    }
