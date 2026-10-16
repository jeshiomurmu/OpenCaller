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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
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
        val result = decideWithinBudget(call)
        return result.copy(latencyMs = started.elapsedNow().inWholeMilliseconds.toInt())
    }

    // Any failure inside the decision means "let the call ring" (docs/rules.md §2), whatever its type.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun decideWithinBudget(call: ScreeningCall): ScreeningResult {
        val work = scope.async { decide(call) }
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

    private suspend fun decide(call: ScreeningCall): ScreeningResult {
        val handle = call.handle?.trim().orEmpty()
        if (handle.isNotEmpty() && device.emergencyNumbers.isEmergency(handle)) {
            return ScreeningResult(Decision.ALLOW, ScreeningReason.EMERGENCY, call.direction, CallerInfo(number = null))
        }
        val region = device.homeRegion.homeRegion()
        val number = handle.takeIf { it.isNotEmpty() }?.let { normalizer.fromHandle(it, region ?: UNKNOWN_REGION) }
        val caller = describer.describe(number, call)
        return when {
            call.direction == Direction.OUTGOING -> allow(ScreeningReason.OUTGOING, call, caller)
            handle.isEmpty() -> decideHidden(device.settings.current(), call, caller)
            number == null -> allow(ScreeningReason.UNKNOWN_FORMAT, call, caller)
            else -> decideNumber(number, region, device.settings.current(), call, caller)
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

    // Steps 2–4, then the settings (6–7), then the spam list and the caller card (8–11).
    private suspend fun decideNumber(
        number: PhoneNumber,
        region: String?,
        prefs: UserPreferences,
        call: ScreeningCall,
        caller: CallerInfo,
    ): ScreeningResult {
        val contactName = device.contacts.displayName(number)
        val decided =
            if (contactName != null) {
                allow(ScreeningReason.CONTACT, call, caller.copy(displayName = contactName, nameSource = NameSource.CONTACT))
            } else {
                matcher.firstMatch(lists.rules.callRules(), number)?.let { ruleResult(it, call, caller) }
                    ?: blockedBySettings(number, region, prefs, call, caller)
            }
        return decided ?: decideBySpamList(number, prefs, call, caller)
    }

    // Steps 6 and 7. Without the contacts permission everyone looks like a non-contact, so step 7 never blocks then
    // (the blocking settings show a banner instead).
    private suspend fun blockedBySettings(
        number: PhoneNumber,
        region: String?,
        prefs: UserPreferences,
        call: ScreeningCall,
        caller: CallerInfo,
    ): ScreeningResult? {
        val reason =
            when {
                prefs.blockForeignNumbers && region != null && describer.isForeign(number, region, prefs) -> {
                    ScreeningReason.FOREIGN
                }

                prefs.blockNonContacts && device.contacts.canRead() && !lists.verifiedBusinesses.isVerifiedBusiness(number) -> {
                    ScreeningReason.NON_CONTACT
                }

                else -> {
                    null
                }
            }
        return reason?.let { ScreeningResult(Decision.BLOCK, it, call.direction, caller) }
    }

    // Step 8, then what the caller card shows for a call that rings.
    private suspend fun decideBySpamList(
        number: PhoneNumber,
        prefs: UserPreferences,
        call: ScreeningCall,
        caller: CallerInfo,
    ): ScreeningResult {
        val verdict = lists.spamList.find(number)?.let { describer.verdict(it.score, it.category.ifBlank { null }, it.reportCount, prefs) }
        return if (verdict?.level == RiskLevel.LIKELY_SPAM) {
            val decision = if (prefs.blockTopSpammers) Decision.BLOCK else Decision.SILENCE
            ScreeningResult(decision, ScreeningReason.SPAM_LIST, call.direction, caller.copy(spamVerdict = verdict))
        } else {
            allow(ScreeningReason.NO_MATCH, call, describer.enrich(number, caller, verdict, prefs))
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
