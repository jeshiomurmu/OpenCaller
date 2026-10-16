package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType

/** A user-defined block/silence/allow rule. */
@Entity(tableName = "block_rule")
data class BlockRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: RuleType,
    val pattern: String,
    val action: RuleAction,
    val appliesTo: RuleTarget,
    val createdAt: Long,
    val enabled: Boolean,
)
