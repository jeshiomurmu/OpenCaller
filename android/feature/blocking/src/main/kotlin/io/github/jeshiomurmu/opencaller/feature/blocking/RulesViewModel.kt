package io.github.jeshiomurmu.opencaller.feature.blocking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleProblem
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleValidation
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleValidator
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningCall
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** What "Test a number" found: what the next call from that number would do, and why. */
sealed interface TestOutcome {
    /** The input isn't a phone number. */
    data object NotANumber : TestOutcome

    /** The engine's answer. [rulePattern] is the deciding rule's pattern, if a rule decided. */
    data class Result(
        val display: String,
        val decision: Decision,
        val reason: ScreeningReason,
        val rulePattern: String?,
        val risk: RiskLevel,
    ) : TestOutcome
}

/** Short messages shown once, as a snackbar. */
sealed interface RulesMessage {
    /** A block rule for one number was added; Undo removes rule [ruleId]. */
    data class Blocked(
        val ruleId: Long,
        val masked: String,
    ) : RulesMessage

    /** A rule was added. */
    data object Added : RulesMessage

    /** Saving failed. */
    data object Failed : RulesMessage
}

/** The rule being typed in the editor. */
data class RuleDraft(
    val type: RuleType = RuleType.EXACT,
    val pattern: String = "",
    val action: RuleAction = RuleAction.BLOCK,
    val problem: RuleProblem? = null,
    val saving: Boolean = false,
)

/** Rules editor screen. */
data class RulesUiState(
    val loading: Boolean = true,
    val rules: List<BlockRule> = emptyList(),
    val draft: RuleDraft = RuleDraft(),
    val testInput: String = "",
    val testing: Boolean = false,
    val testOutcome: TestOutcome? = null,
    val message: RulesMessage? = null,
)

/** User actions in the rules editor. */
sealed interface RulesEvent {
    /** The draft changed. */
    data class EditDraft(
        val draft: RuleDraft,
    ) : RulesEvent

    /** Validate and save the draft. */
    data object SaveDraft : RulesEvent

    /** Turn rule [id] on or off. */
    data class SetEnabled(
        val id: Long,
        val enabled: Boolean,
    ) : RulesEvent

    /** Delete rule [id]. */
    data class Delete(
        val id: Long,
    ) : RulesEvent

    /** Undo the block shown in the snackbar. */
    data class UndoBlock(
        val ruleId: Long,
    ) : RulesEvent

    /** The "Test a number" text changed. */
    data class EditTest(
        val input: String,
    ) : RulesEvent

    /** Run the test. */
    data object RunTest : RulesEvent

    /** Test from a rule's row: fills in its number. */
    data class TestRule(
        val rule: BlockRule,
    ) : RulesEvent

    /** The snackbar was shown. */
    data object MessageShown : RulesEvent
}

/**
 * The rules editor (docs/design.md §5 RuleRow): add exact, prefix, country and pattern rules, validated before saving
 * (slow patterns are rejected, TC-BLK-004), and "Test a number", which runs the real screening engine without
 * logging anything.
 */
@HiltViewModel
class RulesViewModel
    @Inject
    constructor(
        private val rules: BlockRuleRepository,
        private val validator: RuleValidator,
        private val engine: ScreeningEngine,
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
        private val dispatchers: DispatcherProvider,
    ) : ViewModel() {
        private val local = MutableStateFlow(RulesUiState())

        /** Screen state. */
        val uiState: StateFlow<RulesUiState> =
            combine(rules.observeAll(), local) { saved, state ->
                state.copy(loading = false, rules = saved.getOrNull().orEmpty())
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RulesUiState())

        /** Handles [event]. */
        fun onEvent(event: RulesEvent) {
            when (event) {
                is RulesEvent.EditDraft -> update { it.copy(draft = event.draft.copy(problem = null)) }
                RulesEvent.SaveDraft -> saveDraft()
                is RulesEvent.SetEnabled -> launchSave { rules.setEnabled(event.id, event.enabled) }
                is RulesEvent.Delete -> launchSave { rules.delete(event.id) }
                is RulesEvent.UndoBlock -> launchSave { rules.delete(event.ruleId) }
                is RulesEvent.EditTest -> update { it.copy(testInput = event.input, testOutcome = null) }
                RulesEvent.RunTest -> runTest(local.value.testInput)
                is RulesEvent.TestRule -> testRule(event.rule)
                RulesEvent.MessageShown -> update { it.copy(message = null) }
            }
        }

        private fun saveDraft() {
            val draft = local.value.draft
            if (draft.saving) return
            update { it.copy(draft = draft.copy(saving = true)) }
            viewModelScope.launch {
                val validation = withContext(dispatchers.default) { validator.validate(draft.type, draft.pattern, homeRegion.homeRegion()) }
                when (validation) {
                    is RuleValidation.Invalid -> update { it.copy(draft = draft.copy(problem = validation.problem, saving = false)) }
                    is RuleValidation.Valid -> save(draft, validation.pattern)
                }
            }
        }

        private suspend fun save(
            draft: RuleDraft,
            pattern: String,
        ) {
            val message =
                when (val saved = rules.add(draft.type, pattern, draft.action)) {
                    is AppResult.Failure -> {
                        RulesMessage.Failed
                    }

                    is AppResult.Success -> {
                        if (draft.type == RuleType.EXACT && draft.action == RuleAction.BLOCK) {
                            RulesMessage.Blocked(saved.value, normalizer.masked(PhoneNumber(pattern)))
                        } else {
                            RulesMessage.Added
                        }
                    }
                }
            val next =
                if (message ==
                    RulesMessage.Failed
                ) {
                    draft.copy(saving = false)
                } else {
                    RuleDraft(type = draft.type, action = draft.action)
                }
            update { it.copy(draft = next, message = message) }
        }

        private fun testRule(rule: BlockRule) {
            val input = if (rule.type == RuleType.EXACT || rule.type == RuleType.PREFIX) rule.pattern else ""
            update { it.copy(testInput = input, testOutcome = null) }
            if (rule.type == RuleType.EXACT) runTest(input)
        }

        private fun runTest(input: String) {
            val number = normalizer.normalize(input, homeRegion.homeRegion() ?: UNKNOWN_REGION).getOrNull()
            if (number == null) {
                update { it.copy(testOutcome = TestOutcome.NotANumber) }
                return
            }
            update { it.copy(testing = true) }
            viewModelScope.launch {
                val result = engine.screen(ScreeningCall(number.e164, Direction.INCOMING))
                val outcome =
                    TestOutcome.Result(
                        display = normalizer.format(number, homeRegion.homeRegion()),
                        decision = result.decision,
                        reason = result.reason,
                        rulePattern = result.matchedRule?.pattern,
                        risk = result.caller.spamVerdict?.level ?: RiskLevel.NONE,
                    )
                update { it.copy(testing = false, testOutcome = outcome) }
            }
        }

        private fun launchSave(save: suspend () -> AppResult<Unit>) {
            viewModelScope.launch {
                if (save() is AppResult.Failure) update { it.copy(message = RulesMessage.Failed) }
            }
        }

        private fun update(change: (RulesUiState) -> RulesUiState) {
            local.value = change(local.value)
        }

        private companion object {
            const val STOP_TIMEOUT_MILLIS = 5_000L
            const val UNKNOWN_REGION = "ZZ"
        }
    }
