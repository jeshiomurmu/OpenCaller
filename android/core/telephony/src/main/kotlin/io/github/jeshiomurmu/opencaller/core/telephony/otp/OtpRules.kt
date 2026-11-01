package io.github.jeshiomurmu.opencaller.core.telephony.otp

/**
 * The rules of the OTP safety guard and the burst alert (docs/architecture.md §4.5, PRD F-PR-11/12), without any
 * Android code, so they are tested as they are.
 */
object OtpRules {
    /** A code within this long after a call with a stranger ended still counts. */
    const val AFTER_CALL_MILLIS: Long = 2 * 60 * 1000L

    /** A missed incoming call that rang at most this long can be a flash call. */
    const val FLASH_CALL_MILLIS: Long = 3_000L

    /** The burst alert's window, code count and distinct senders. */
    const val BURST_WINDOW_MILLIS: Long = 10 * 60 * 1000L
    const val BURST_CODES: Int = 3
    const val BURST_SENDERS: Int = 2

    /**
     * The call with a stranger that makes a code arriving at [nowMillis] dangerous: a conversation (answered, or
     * placed by the user) that is live or ended at most [AFTER_CALL_MILLIS] before. A missed call is no conversation,
     * so a flash call never counts. `null` when there is none.
     */
    fun riskyCall(
        calls: List<ObservedCall>,
        nowMillis: Long,
    ): ObservedCall? =
        calls.firstOrNull { call ->
            val conversation = call.answeredAtMillis != null || !call.incoming
            val recent = call.isLive || nowMillis - (call.endedAtMillis ?: nowMillis) <= AFTER_CALL_MILLIS
            call.party == CallParty.STRANGER && conversation && recent
        }

    /** True when [call] looks like a flash call: incoming, never answered, rang at most [FLASH_CALL_MILLIS]. */
    fun isFlashCall(call: ObservedCall): Boolean {
        val ended = call.endedAtMillis ?: return false
        return call.incoming && call.answeredAtMillis == null && ended - call.startedAtMillis <= FLASH_CALL_MILLIS
    }

    /**
     * True when the codes received at [codes] (time and sender key; the newest included) make a burst: at least
     * [BURST_CODES] within [BURST_WINDOW_MILLIS] before [nowMillis] from at least [BURST_SENDERS] senders.
     */
    fun isBurst(
        codes: List<Pair<Long, String>>,
        nowMillis: Long,
    ): Boolean {
        val recent = codes.filter { (at, _) -> at in (nowMillis - BURST_WINDOW_MILLIS)..nowMillis }
        return recent.size >= BURST_CODES && recent.map { it.second }.distinct().size >= BURST_SENDERS
    }
}
