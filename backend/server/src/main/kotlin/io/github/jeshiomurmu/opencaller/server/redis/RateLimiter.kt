package io.github.jeshiomurmu.opencaller.server.redis

import kotlin.time.Duration

/** A limit of [maxHits] per [window], e.g. 3 per hour (docs/rules.md §7). */
data class RateLimit(
    val name: String,
    val maxHits: Int,
    val window: Duration,
)

/** Outcome of one counted request: allowed, or refused with the seconds until the window resets. */
sealed interface RateDecision {
    data object Allowed : RateDecision

    data class Limited(
        val retryAfterSeconds: Long,
    ) : RateDecision
}

/**
 * Fixed-window counters in Redis: the first hit creates `rl:<name>:<subject>` with the window as its TTL, every
 * hit increments it, and a count above the limit is refused until the key expires. Refused hits still count, so
 * hammering an endpoint doesn't shorten the wait.
 */
class RateLimiter(
    private val redis: Redis,
) {
    /** Counts [cost] hits (e.g. the reports in one batch) of [subject] against [limit]. */
    suspend fun hit(
        limit: RateLimit,
        subject: String,
        cost: Int = 1,
    ): RateDecision {
        require(cost >= 1) { "cost must be positive" }
        val (count, ttl) =
            redis.evalLongs(
                HIT_SCRIPT,
                listOf("rl:${limit.name}:$subject"),
                listOf(limit.window.inWholeSeconds.toString(), cost.toString()),
            )
        return if (count <= limit.maxHits) RateDecision.Allowed else RateDecision.Limited(retryAfterSeconds = ttl.coerceAtLeast(1))
    }

    private companion object {
        // KEYS[1] counter key, ARGV[1] window in seconds, ARGV[2] cost. Returns {count, ttl seconds}.
        const val HIT_SCRIPT = """
            local count = redis.call('INCRBY', KEYS[1], tonumber(ARGV[2]))
            if count == tonumber(ARGV[2]) then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end
            local ttl = redis.call('TTL', KEYS[1])
            if ttl < 0 then
              redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
              ttl = tonumber(ARGV[1])
            end
            return {count, ttl}
        """
    }
}
