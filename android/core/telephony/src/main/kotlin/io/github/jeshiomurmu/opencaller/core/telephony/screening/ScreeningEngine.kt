package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.TimeSource

/**
 * Decides what happens to a call, on the device (docs/architecture.md §4.1). Steps, in order:
 * emergency numbers and outgoing calls are always allowed; then 1 read the number (none ⇒ hidden), 2 contacts ring,
 * 3 allow rules, 4 block/silence rules, 5 hidden numbers, 6 foreign numbers, 7 non-contacts (verified businesses
 * still ring), 8 the spam list at the user's threshold, 9 the reputation cache, 10 the network lookup (a no-op until
 * Phase 5) and 11 the carrier's STIR/SHAKEN verdict, which only annotate the caller card.
 *
 * Every decision finishes within [ScreeningConfig.budgetMillis]: the work runs in the engine's own scope and is
 * abandoned at the deadline, so even a lookup stuck in blocking I/O can't delay the call. A timeout or any failure
 * lets the call ring (fail open) with reason [ScreeningReason.TIMEOUT] or [ScreeningReason.ERROR].
 */
class ScreeningEngine(
    private val device: DeviceSources,
    private val lists: ListSources,
    private val normalizer: PhoneNumberNormalizer,
    dispatchers: DispatcherProvider,
    private val timeSource: TimeSource,
    private val config: ScreeningConfig,
    regexClock: () -> Long = System::nanoTime,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val matcher = RuleMatcher(normalizer::countryOf, TimeLimitedRegex(config.regexBudgetMillis, regexClock))
    private val describer = CallerDescriber(lists, normalizer)

    /** Screens [call]. Never throws except to propagate the caller's own cancellation. */
    suspend fun screen(call: ScreeningCall): ScreeningResult {
        val started = timeSource.markNow()
        val steps = StepTimes(timeSource)
        val result = decideWithinBudget(call, steps)
        val latencyMs = started.elapsedNow().inWholeMilliseconds.toInt()
        // Debug builds only (no tree is planted in release); durations and the reason code, never the number.
        Timber.d("Screening timings: %s in %d ms; %s", result.reason.code, latencyMs, steps)
        return result.copy(latencyMs = latencyMs)
    }

    // Any failure inside the decision means "let the call ring" (docs/rules.md §2), whatever its type.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun decideWithinBudget(
        call: ScreeningCall,
        steps: StepTimes,
    ): ScreeningResult {
        val work = scope.async { decide(call, steps) }
        return try {
            withTimeoutOrNull(config.budgetMillis) { work.await() } ?: failOpen(call, ScreeningReason.TIMEOUT).also {
                work.cancel()
                Timber.w("Screening exceeded %d ms; letting the call ring", config.budgetMillis)
            }
        } catch (e: CancellationException) {
            work.cancel()
            currentCoroutineContext().ensureActive()
            Timber.w(e, "Screening was cancelled; letting the call ring")
            failOpen(call, ScreeningReason.ERROR)
        } catch (e: Exception) {
            Timber.w(e, "Screening failed; letting the call ring")
            failOpen(call, ScreeningReason.ERROR)
        }
    }

    private fun failOpen(
        call: ScreeningCall,
        reason: ScreeningReason,
    ) = ScreeningResult(Decision.ALLOW, reason, call.direction, CallerInfo(number = null, verification = call.verification))

    private suspend fun decide(
        call: ScreeningCall,
        steps: StepTimes,
    ): ScreeningResult {
        val handle = call.handle?.trim().orEmpty()
        if (handle.isNotEmpty() && steps.measure("emergency") { device.emergencyNumbers.isEmergency(handle) }) {
            return ScreeningResult(Decision.ALLOW, ScreeningReason.EMERGENCY, call.direction, CallerInfo(number = null))
        }
        val region = steps.measure("region") { device.homeRegion.homeRegion() }
        val number = handle.takeIf { it.isNotEmpty() }?.let { normalizer.fromHandle(it, region ?: UNKNOWN_REGION) }
        val caller = steps.measure("describe") { describer.describe(number, call) }
        return when {
            call.direction == Direction.OUTGOING -> allow(ScreeningReason.OUTGOING, call, caller)
            handle.isEmpty() -> decideHidden(device.settings.current(), call, caller)
            number == null -> allow(ScreeningReason.UNKNOWN_FORMAT, call, caller)
            else -> decideNumber(number, region, call, caller, steps)
        }
    }

    // Step 5.
    private fun decideHidden(
        prefs: UserPreferences,
        call: ScreeningCall,
        caller: CallerInfo,
    ): ScreeningResult =
        if (prefs.blockHiddenNumbers) {
            ScreeningResult(Decision.BLOCK, ScreeningReason.HIDDEN, call.direction, caller)
        } else {
            allow(ScreeningReason.NO_MATCH, call, caller)
        }

    // Steps 2–4, then the settings (6–7), then the spam list and the caller card (8–11). The local lookups start
    // together (the database reads as one chain), so the call waits for the slowest of them rather than for their
    // sum; the decision still takes them in order. A lookup's failure only counts if the decision needs its answer,
    // and lookups still running when the decision is made are cancelled. The network (step 10) is never prefetched.
    private suspend fun decideNumber(
        number: PhoneNumber,
        region: String?,
        call: ScreeningCall,
        caller: CallerInfo,
        steps: StepTimes,
    ): ScreeningResult =
        supervisorScope {
            val lookups = LocalLookups(this, number, steps)
            try {
                val prefs = lookups.prefs.await()
                val decided =
                    lookups.contactName.await()?.let { name ->
                        allow(ScreeningReason.CONTACT, call, caller.copy(displayName = name, nameSource = NameSource.CONTACT))
                    } ?: matcher.firstMatch(lookups.rules.await(), number)?.let { ruleResult(it, call, caller) }
                        ?: blockedBySettings(
                            number,
                            region,
                            prefs,
                            lookups,
                        )?.let { ScreeningResult(Decision.BLOCK, it, call.direction, caller) }
                decided ?: decideBySpamList(number, prefs, lookups, call, caller)
            } finally {
                coroutineContext.cancelChildren()
            }
        }

    // Steps 6 and 7. Without the contacts permission everyone looks like a non-contact, so step 7 never blocks then
    // (the blocking settings show a banner instead).
    private suspend fun blockedBySettings(
        number: PhoneNumber,
        region: String?,
        prefs: UserPreferences,
        lookups: LocalLookups,
    ): ScreeningReason? =
        when {
            prefs.blockForeignNumbers && region != null && describer.isForeign(number, region, prefs) -> {
                ScreeningReason.FOREIGN
            }

            prefs.blockNonContacts && device.contacts.canRead() && !lookups.verified.await() -> {
                ScreeningReason.NON_CONTACT
            }

            else -> {
                null
            }
        }

    // Step 8, then what the caller card shows for a call that rings.
    private suspend fun decideBySpamList(
        number: PhoneNumber,
        prefs: UserPreferences,
        lookups: LocalLookups,
        call: ScreeningCall,
        caller: CallerInfo,
    ): ScreeningResult {
        val verdict = lookups.spamHit.await()?.let { describer.verdict(it.score, it.category.ifBlank { null }, it.reportCount, prefs) }
        return if (verdict?.level == RiskLevel.LIKELY_SPAM) {
            val decision = if (prefs.blockTopSpammers) Decision.BLOCK else Decision.SILENCE
            ScreeningResult(decision, ScreeningReason.SPAM_LIST, call.direction, caller.copy(spamVerdict = verdict))
        } else {
            val known = KnownCaller(verified = lookups.verified.await(), cached = lookups.reputation.await())
            allow(ScreeningReason.NO_MATCH, call, describer.enrich(number, caller, verdict, prefs, known))
        }
    }

    // On-device answers for one number, fetched concurrently (see decideNumber). The rules, the spam list and the
    // reputation cache live in one encrypted database and are read one after another: concurrent readers make the
    // connection pool open another connection, and opening one derives the SQLCipher key (about 0.5 s on the CI
    // emulators). join() waits without rethrowing, so a failed read still only counts where its answer is needed.
    private inner class LocalLookups(
        scope: CoroutineScope,
        number: PhoneNumber,
        steps: StepTimes,
    ) {
        val prefs = scope.async { steps.measure("settings") { device.settings.current() } }
        val contactName = scope.async { steps.measure("contacts") { device.contacts.displayName(number) } }
        val verified = scope.async { steps.measure("verified") { lists.verifiedBusinesses.isVerifiedBusiness(number) } }
        val rules = scope.async { steps.measure("rules") { lists.rules.callRules() } }
        val spamHit =
            scope.async {
                rules.join()
                steps.measure("spam") { lists.spamList.find(number) }
            }
        val reputation =
            scope.async {
                spamHit.join()
                steps.measure("reputation") { lists.reputation.find(number) }
            }
    }

    private fun ruleResult(
        rule: BlockRule,
        call: ScreeningCall,
        caller: CallerInfo,
    ): ScreeningResult =
        when (rule.action) {
            RuleAction.ALLOW -> ScreeningResult(Decision.ALLOW, ScreeningReason.ALLOW_RULE, call.direction, caller, rule)
            RuleAction.BLOCK -> ScreeningResult(Decision.BLOCK, ScreeningReason.RULE, call.direction, caller, rule)
            RuleAction.SILENCE -> ScreeningResult(Decision.SILENCE, ScreeningReason.RULE, call.direction, caller, rule)
        }

    private fun allow(
        reason: ScreeningReason,
        call: ScreeningCall,
        caller: CallerInfo,
    ) = ScreeningResult(Decision.ALLOW, reason, call.direction, caller)

    private companion object {
        const val UNKNOWN_REGION = "ZZ"
    }
}

/** How long each step of one decision took, for the debug log. Lookups run concurrently, so they can overlap. */
private class StepTimes(
    private val timeSource: TimeSource,
) {
    private val millis = ConcurrentHashMap<String, Long>()

    inline fun <T> measure(
        step: String,
        block: () -> T,
    ): T {
        val started = timeSource.markNow()
        try {
            return block()
        } finally {
            millis[step] = started.elapsedNow().inWholeMilliseconds
        }
    }

    override fun toString(): String = millis.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
}
