package io.github.jeshiomurmu.opencaller.feature.callerid

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ReportRepository
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Block and Not spam for a number that just called (after-call notification and after-call screen). */
interface AfterCallActions {
    /** Adds an exact block rule for [number]. */
    suspend fun block(number: PhoneNumber): AppResult<Unit>

    /**
     * Marks [number] as not spam: removes its exact block and silence rules, adds an exact allow rule (so it is never
     * blocked here again) and queues a not-spam report.
     */
    suspend fun notSpam(number: PhoneNumber): AppResult<Unit>
}

/** [AfterCallActions] on the rules and reports repositories. */
class RepositoryAfterCallActions
    @Inject
    constructor(
        private val rules: BlockRuleRepository,
        private val reports: ReportRepository,
    ) : AfterCallActions {
        override suspend fun block(number: PhoneNumber): AppResult<Unit> = rules.add(RuleType.EXACT, number.e164, RuleAction.BLOCK).map {}

        override suspend fun notSpam(number: PhoneNumber): AppResult<Unit> =
            rules
                .deleteExactRules(number, setOf(RuleAction.BLOCK, RuleAction.SILENCE))
                .flatMap { rules.add(RuleType.EXACT, number.e164, RuleAction.ALLOW) }
                .flatMap { reports.queue(number, ReportLabel.NOT_SPAM) }
                .map {}
    }

/**
 * Who was on calls that just ended, kept in memory for the after-call screen (an activity can't be handed a
 * [CallerInfo] in its intent). Entries are taken once.
 */
@Singleton
class EndedCalls
    @Inject
    constructor() {
        private val callers = ConcurrentHashMap<PhoneNumber, CallerInfo>()

        /** Keeps [caller] (it has a number) for the screen that opens next. */
        fun put(caller: CallerInfo) {
            caller.number?.let { callers[it] = caller }
        }

        /** The caller for [number], if the screen is opening for it. */
        fun take(number: PhoneNumber): CallerInfo? = callers.remove(number)
    }
