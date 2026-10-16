package io.github.jeshiomurmu.opencaller.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.jeshiomurmu.opencaller.core.model.FamilyRole

/** A member of the user's family-protection group. */
@Entity(tableName = "family_member")
data class FamilyMemberEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val role: FamilyRole,
    val joinedAt: Long,
)
