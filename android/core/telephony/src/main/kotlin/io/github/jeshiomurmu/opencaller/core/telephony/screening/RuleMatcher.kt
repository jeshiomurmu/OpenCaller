package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import timber.log.Timber
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.regex.PatternSyntaxException

/** A regular expression ran past its time budget. */
class RegexTimeoutException : RuntimeException("Regular expression exceeded its time budget")

/**
 * Wraps [text] so every character read checks [deadlineNanos]: on engines that read their input through
 * [CharSequence.get] (OpenJDK, used by unit tests) a catastrophic pattern stops at the deadline.
 */
internal class DeadlineCharSequence(
    private val text: CharSequence,
    private val deadlineNanos: Long,
    private val nanoTime: () -> Long,
) : CharSequence {
    override val length: Int get() = text.length

    override fun get(index: Int): Char {
        if (nanoTime() - deadlineNanos > 0) throw RegexTimeoutException()
        return text[index]
    }

    override fun subSequence(
        startIndex: Int,
        endIndex: Int,
    ): CharSequence = DeadlineCharSequence(text.subSequence(startIndex, endIndex), deadlineNanos, nanoTime)

    override fun toString(): String = text.toString()
}

/**
 * Runs regular expressions with a hard time limit (TC-BLK-004). Android's java.util.regex is ICU, which copies the
 * input and never calls [CharSequence.get], so a [DeadlineCharSequence] alone can't stop it there. Each match
 * therefore also runs on a small pool of daemon threads and is abandoned after [budgetMillis]; a pattern that
 * times out [MAX_STRIKES] times is skipped from then on, so a runaway pattern can't occupy the pool. The editor
 * rejects slow patterns before they are saved ([RuleValidator]), so this is the second line of defence.
 */
class TimeLimitedRegex(
    private val budgetMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val cache = ConcurrentHashMap<String, Regex>()
    private val strikes = ConcurrentHashMap<String, Int>()
    private val executor =
        ThreadPoolExecutor(0, MAX_THREADS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "opencaller-regex").apply { isDaemon = true }
        }

    /**
     * True when [pattern] matches somewhere in [input]. Throws [RegexTimeoutException] when it runs out of time (or
     * was skipped after timing out before) and [PatternSyntaxException] for an invalid pattern.
     */
    fun containsMatch(
        pattern: String,
        input: String,
    ): Boolean {
        if ((strikes[pattern] ?: 0) >= MAX_STRIKES) throw RegexTimeoutException()
        val regex = cache[pattern] ?: Regex(pattern).also { compiled -> if (cache.size < MAX_CACHED) cache[pattern] = compiled }
        val deadline = nanoTime() + budgetMillis * NANOS_PER_MILLI
        val match = submit { regex.containsMatchIn(DeadlineCharSequence(input, deadline, nanoTime)) }
        return runCatching { match.get(budgetMillis, TimeUnit.MILLISECONDS) }.getOrElse { failure ->
            match.cancel(true)
            throw timeoutOrCause(pattern, failure)
        }
    }

    // Every regex thread busy with runaway patterns means this one can't run in time either.
    private fun submit(match: () -> Boolean): Future<Boolean> =
        try {
            executor.submit(Callable(match))
        } catch (e: RejectedExecutionException) {
            throw RegexTimeoutException().apply { initCause(e) }
        }

    // A timeout (watchdog or deadline) counts as a strike against the pattern; anything else is rethrown as it was.
    private fun timeoutOrCause(
        pattern: String,
        failure: Throwable,
    ): Throwable {
        val cause = if (failure is ExecutionException) failure.cause ?: failure else failure
        return if (cause is TimeoutException || cause is RegexTimeoutException) {
            strikes.merge(pattern, 1, Int::plus)
            if (cause is RegexTimeoutException) cause else RegexTimeoutException().apply { initCause(cause) }
        } else {
            cause
        }
    }

    private companion object {
        const val MAX_CACHED = 64
        const val MAX_STRIKES = 2
        const val MAX_THREADS = 4
        const val KEEP_ALIVE_SECONDS = 30L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * Matches user rules against a number (docs/architecture.md §4.1 steps 3–4): an ALLOW rule beats any BLOCK rule,
 * and BLOCK beats SILENCE. EXACT compares the E.164 string, PREFIX its start, COUNTRY the number's country, and
 * REGEX searches the E.164 string within a time budget; a regex that times out or is invalid never matches.
 */
class RuleMatcher(
    private val countryOf: (PhoneNumber) -> String?,
    private val regex: TimeLimitedRegex,
) {
    /** The deciding rule for [number], or `null` when none matches. */
    fun firstMatch(
        rules: List<BlockRule>,
        number: PhoneNumber,
    ): BlockRule? =
        PRECEDENCE.firstNotNullOfOrNull { action ->
            rules.firstOrNull { it.enabled && it.action == action && matches(it, number) }
        }

    /** True when [rule] matches [number], ignoring whether it is enabled. */
    fun matches(
        rule: BlockRule,
        number: PhoneNumber,
    ): Boolean =
        when (rule.type) {
            RuleType.EXACT -> number.e164 == rule.pattern
            RuleType.PREFIX -> rule.pattern.isNotEmpty() && number.e164.startsWith(rule.pattern)
            RuleType.COUNTRY -> countryOf(number)?.equals(rule.pattern, ignoreCase = true) == true
            RuleType.REGEX -> regexMatches(rule, number)
        }

    private fun regexMatches(
        rule: BlockRule,
        number: PhoneNumber,
    ): Boolean =
        try {
            regex.containsMatch(rule.pattern, number.e164)
        } catch (e: RegexTimeoutException) {
            Timber.w(e, "Regex rule %d timed out; treated as not matching", rule.id)
            false
        } catch (e: PatternSyntaxException) {
            Timber.w(e, "Regex rule %d is invalid; treated as not matching", rule.id)
            false
        }

    private companion object {
        val PRECEDENCE = listOf(RuleAction.ALLOW, RuleAction.BLOCK, RuleAction.SILENCE)
    }
}
