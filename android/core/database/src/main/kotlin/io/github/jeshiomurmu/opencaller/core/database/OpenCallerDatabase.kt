package io.github.jeshiomurmu.opencaller.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.jeshiomurmu.opencaller.core.database.dao.BlockRuleDao
import io.github.jeshiomurmu.opencaller.core.database.dao.FamilyMemberDao
import io.github.jeshiomurmu.opencaller.core.database.dao.LookupBucketDao
import io.github.jeshiomurmu.opencaller.core.database.dao.MessageMetaDao
import io.github.jeshiomurmu.opencaller.core.database.dao.NumberInfoDao
import io.github.jeshiomurmu.opencaller.core.database.dao.OtpAppHashDao
import io.github.jeshiomurmu.opencaller.core.database.dao.RecordingDao
import io.github.jeshiomurmu.opencaller.core.database.dao.ReportOutboxDao
import io.github.jeshiomurmu.opencaller.core.database.dao.ScheduledSmsDao
import io.github.jeshiomurmu.opencaller.core.database.dao.ScreeningEventDao
import io.github.jeshiomurmu.opencaller.core.database.dao.SenderIdDao
import io.github.jeshiomurmu.opencaller.core.database.dao.SpamListDao
import io.github.jeshiomurmu.opencaller.core.database.dao.VerificationRangeDao
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.FamilyMemberEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.LookupBucketEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.MessageMetaEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.NumberInfoEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.OtpAppHashEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.RecordingEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ReportOutboxEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScheduledSmsEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.ScreeningEventEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.SenderIdEntity
import io.github.jeshiomurmu.opencaller.core.database.entity.SpamListEntry
import io.github.jeshiomurmu.opencaller.core.database.entity.VerificationRangeEntity

/**
 * The app's SQLCipher-encrypted Room database (docs/architecture.md §6). Open it through
 * [EncryptedDatabaseProvider]. Every schema change bumps [version] with an explicit `Migration` and a
 * migration test; schemas are exported to `core/database/schemas/`.
 */
@Database(
    entities = [
        SpamListEntry::class,
        NumberInfoEntity::class,
        LookupBucketEntity::class,
        BlockRuleEntity::class,
        ScreeningEventEntity::class,
        MessageMetaEntity::class,
        SenderIdEntity::class,
        OtpAppHashEntity::class,
        VerificationRangeEntity::class,
        ReportOutboxEntity::class,
        RecordingEntity::class,
        ScheduledSmsEntity::class,
        FamilyMemberEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@Suppress("TooManyFunctions") // One accessor per DAO is how Room exposes them.
abstract class OpenCallerDatabase : RoomDatabase() {
    /** On-device spam list. */
    abstract fun spamListDao(): SpamListDao

    /** Reputation cache. */
    abstract fun numberInfoDao(): NumberInfoDao

    /** Fetched lookup buckets. */
    abstract fun lookupBucketDao(): LookupBucketDao

    /** User block rules. */
    abstract fun blockRuleDao(): BlockRuleDao

    /** Screening decisions. */
    abstract fun screeningEventDao(): ScreeningEventDao

    /** SMS metadata. */
    abstract fun messageMetaDao(): MessageMetaDao

    /** Sender-ID registry. */
    abstract fun senderIdDao(): SenderIdDao

    /** SMS Retriever app hashes. */
    abstract fun otpAppHashDao(): OtpAppHashDao

    /** Flash-call verification ranges. */
    abstract fun verificationRangeDao(): VerificationRangeDao

    /** Report upload queue. */
    abstract fun reportOutboxDao(): ReportOutboxDao

    /** Call recordings. */
    abstract fun recordingDao(): RecordingDao

    /** Scheduled SMS. */
    abstract fun scheduledSmsDao(): ScheduledSmsDao

    /** Family group members. */
    abstract fun familyMemberDao(): FamilyMemberDao
}
