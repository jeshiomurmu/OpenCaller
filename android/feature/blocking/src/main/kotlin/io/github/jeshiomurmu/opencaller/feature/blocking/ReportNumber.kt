package io.github.jeshiomurmu.opencaller.feature.blocking

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ReportRepository
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.ui.ReportDraft
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Sends a report from the report sheet: queued in the outbox (uploaded when online and signed in) and, for spam with
 * "Also block", an exact block rule.
 */
class ReportNumber
    @Inject
    constructor(
        private val reports: ReportRepository,
        private val rules: BlockRuleRepository,
        account: AccountStatus,
    ) {
        /** True while signed in (otherwise reports wait on the phone). */
        val signedIn: Flow<Boolean> = account.signedIn

        /** Queues [draft] for [number]; true when a block rule was added too. */
        suspend operator fun invoke(
            number: PhoneNumber,
            draft: ReportDraft,
        ): AppResult<Boolean> {
            val block = draft.alsoBlock && draft.label == ReportLabel.SPAM
            return reports
                .queue(number, draft.label, draft.category?.code, draft.comment)
                .flatMap {
                    if (block) rules.add(RuleType.EXACT, number.e164, RuleAction.BLOCK).map { true } else AppResult.Success(false)
                }
        }
    }
