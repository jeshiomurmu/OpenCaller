package io.github.jeshiomurmu.opencaller.feature.blocking

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.model.Decision
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningReason
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningCall
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEngine
import javax.inject.Inject

/**
 * Undo for a blocked call (docs/design.md §7, TC-BLK-015): removes the exact block and silence rules for the number,
 * then asks the engine what the next call would do. If something else would still stop it (a prefix rule, a setting,
 * the spam list), an exact allow rule is added, since allow beats every block.
 */
class UnblockNumber
    @Inject
    constructor(
        private val rules: BlockRuleRepository,
        private val engine: ScreeningEngine,
    ) {
        /** Makes [number] ring next time. */
        suspend operator fun invoke(number: PhoneNumber): AppResult<Unit> =
            rules.deleteExactRules(number, setOf(RuleAction.BLOCK, RuleAction.SILENCE)).flatMap {
                val next = engine.screen(ScreeningCall(number.e164, Direction.INCOMING))
                val rings = next.decision == Decision.ALLOW && next.reason !in FAILED
                if (rings) AppResult.Success(Unit) else rules.add(RuleType.EXACT, number.e164, RuleAction.ALLOW).map {}
            }

        private companion object {
            // A decision that failed open says nothing about the rules, so add the allow rule to be sure.
            val FAILED = setOf(ScreeningReason.TIMEOUT, ScreeningReason.ERROR)
        }
    }
