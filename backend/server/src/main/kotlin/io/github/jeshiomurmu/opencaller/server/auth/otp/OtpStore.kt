package io.github.jeshiomurmu.opencaller.server.auth.otp

import io.github.jeshiomurmu.opencaller.server.auth.E164
import io.github.jeshiomurmu.opencaller.server.redis.Redis
import kotlin.time.Duration

/** Result of checking a code against the stored one. */
sealed interface OtpCheck {
    /** Right code; it has been used up. */
    data object Valid : OtpCheck

    /** Wrong code; [attemptsLeft] more tries before the code is invalidated. */
    data class Wrong(
        val attemptsLeft: Int,
    ) : OtpCheck

    /** Wrong code and no tries left: the code has been deleted. */
    data object Invalidated : OtpCheck

    /** No code for this number: never sent, expired, used or invalidated. */
    data object Missing : OtpCheck
}

/**
 * One pending code per number in Redis (`otp:<e164>`; unlist codes use `otp-unlist:<e164>`), a hash of `h` = keyed
 * code hash and `a` = wrong attempts, with the code's lifetime as TTL. Only the hash is stored. Sending a new code replaces the old one.
 */
class OtpStore(
    private val redis: Redis,
    private val keyPrefix: String = "otp:",
) {
    /** Stores [codeHash] for [number] for [ttl], replacing any earlier code. */
    suspend fun put(
        number: E164,
        codeHash: String,
        ttl: Duration,
    ) {
        redis.evalLong(PUT_SCRIPT, listOf(key(number)), listOf(codeHash, ttl.inWholeSeconds.toString()))
    }

    /** Checks [codeHash]; counts a wrong attempt and deletes the code after [maxAttempts] wrong ones or one right one. */
    suspend fun check(
        number: E164,
        codeHash: String,
        maxAttempts: Int,
    ): OtpCheck {
        val (status, attempts) = redis.evalLongs(CHECK_SCRIPT, listOf(key(number)), listOf(codeHash, maxAttempts.toString()))
        return when (status) {
            STATUS_VALID -> OtpCheck.Valid
            STATUS_WRONG -> OtpCheck.Wrong(attemptsLeft = (maxAttempts - attempts).toInt())
            STATUS_INVALIDATED -> OtpCheck.Invalidated
            else -> OtpCheck.Missing
        }
    }

    /** Removes the pending code (e.g. when it couldn't be delivered). */
    suspend fun delete(number: E164) = redis.delete(key(number))

    private fun key(number: E164) = "$keyPrefix${number.value}"

    private companion object {
        const val STATUS_MISSING = 0L
        const val STATUS_VALID = 1L
        const val STATUS_WRONG = 2L
        const val STATUS_INVALIDATED = 3L

        // KEYS[1] otp key, ARGV[1] code hash, ARGV[2] ttl seconds.
        const val PUT_SCRIPT = """
            redis.call('DEL', KEYS[1])
            redis.call('HSET', KEYS[1], 'h', ARGV[1], 'a', 0)
            redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
            return 1
        """

        // KEYS[1] otp key, ARGV[1] code hash, ARGV[2] max attempts. Returns {status, wrong attempts so far}.
        val CHECK_SCRIPT = """
            local stored = redis.call('HGET', KEYS[1], 'h')
            if not stored then return {$STATUS_MISSING, 0} end
            if stored == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return {$STATUS_VALID, 0}
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'a', 1)
            if attempts >= tonumber(ARGV[2]) then
              redis.call('DEL', KEYS[1])
              return {$STATUS_INVALIDATED, attempts}
            end
            return {$STATUS_WRONG, attempts}
        """
    }
}
