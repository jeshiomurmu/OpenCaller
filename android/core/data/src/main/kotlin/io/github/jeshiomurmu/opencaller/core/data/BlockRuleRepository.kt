package io.github.jeshiomurmu.opencaller.core.data

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.database.DatabaseAccess
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.map as mapEach

/** The user's block, silence and allow rules. Patterns are validated by the caller (`RuleValidator`). */
@Singleton
class BlockRuleRepository
    @Inject
    constructor(
        access: DatabaseAccess,
        dispatchers: DispatcherProvider,
        private val clock: Clock,
    ) {
        private val storage = Storage(access, dispatchers)

        /** All rules, newest first. */
        fun observeAll(): Flow<AppResult<List<BlockRule>>> =
            storage.observe { blockRuleDao().observeAll() }.mapEach { result -> result.map { rules -> rules.map { it.toDomain() } } }

        /** Enabled rules that apply to calls, used by the screening engine. */
        suspend fun enabledCallRules(): AppResult<List<BlockRule>> =
            storage
                .run { blockRuleDao().enabledRules() }
                .map { rules -> rules.map { it.toDomain() }.filter { it.target != RuleTarget.SMS } }

        /** Saves a new enabled rule and returns its id. */
        suspend fun add(
            type: RuleType,
            pattern: String,
            action: RuleAction,
            target: RuleTarget = RuleTarget.CALLS,
        ): AppResult<Long> =
            storage.run {
                blockRuleDao().insert(
                    BlockRuleEntity(
                        type = type,
                        pattern = pattern,
                        action = action,
                        appliesTo = target,
                        createdAt = clock.nowMillis(),
                        enabled = true,
                    ),
                )
            }

        /** Turns rule [id] on or off. */
        suspend fun setEnabled(
            id: Long,
            enabled: Boolean,
        ): AppResult<Unit> = storage.run { blockRuleDao().setEnabled(id, enabled) }.map {}

        /** Deletes rule [id]. */
        suspend fun delete(id: Long): AppResult<Unit> = storage.run { blockRuleDao().deleteById(id) }.map {}

        /** Deletes the exact-number rules for [number] whose action is in [actions]; returns how many were removed. */
        suspend fun deleteExactRules(
            number: PhoneNumber,
            actions: Set<RuleAction>,
        ): AppResult<Int> =
            storage.run {
                val dao = blockRuleDao()
                dao.exactRulesFor(number.e164).filter { it.action in actions }.sumOf { dao.deleteById(it.id) }
            }

        /** Deletes every rule (debug QA reset). */
        suspend fun deleteAll(): AppResult<Int> = storage.run { blockRuleDao().deleteAll() }
    }
