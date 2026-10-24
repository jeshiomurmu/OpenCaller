package io.github.jeshiomurmu.opencaller.feature.calllog

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** [ScreeningHistory] from the screening log (90 days on the device). */
class RepositoryScreeningHistory
    @Inject
    constructor(
        private val events: ScreeningEventRepository,
    ) : ScreeningHistory {
        override fun recent(): Flow<List<ScreeningEvent>> = events.observeRecent(LIMIT).map { it.getOrNull().orEmpty() }

        private companion object {
            // About as many as the call log shows (DeviceCallLog.LIMIT), with room for blocked calls.
            const val LIMIT = 1_000
        }
    }

/** [NumberBlocker] on the user's rules. */
class RuleNumberBlocker
    @Inject
    constructor(
        private val rules: BlockRuleRepository,
    ) : NumberBlocker {
        override suspend fun block(number: PhoneNumber): AppResult<Long> = rules.add(RuleType.EXACT, number.e164, RuleAction.BLOCK)

        override suspend fun undo(ruleId: Long): AppResult<Unit> = rules.delete(ruleId)
    }
