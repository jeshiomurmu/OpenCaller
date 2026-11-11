package io.github.jeshiomurmu.opencaller.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** Schema migrations of [OpenCallerDatabase], each tested in `MigrationTest` (docs/architecture.md §6). */
object Migrations {
    /** Version 2 (Phase 8): the supporter badge in the reputation cache, `number_info.badge`. */
    val MIGRATION_1_2: Migration = SqlMigration(1, 2, "ALTER TABLE `number_info` ADD COLUMN `badge` TEXT")

    /** Version 3 (Phase 9): the verified-business directory of the phone's country, `verified_business`. */
    val MIGRATION_2_3: Migration =
        SqlMigration(
            2,
            3,
            "CREATE TABLE IF NOT EXISTS `verified_business` (`e164` TEXT NOT NULL, `businessId` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`category` TEXT, `callReason` TEXT, `logoPath` TEXT, `logoFile` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`e164`))",
        )

    /** Version 4 (Phase 10): the extended spam list of supporters, `spam_list_extended`. */
    val MIGRATION_3_4: Migration =
        SqlMigration(
            3,
            4,
            "CREATE TABLE IF NOT EXISTS `spam_list_extended` (`e164` TEXT NOT NULL, `score` REAL NOT NULL, `category` TEXT NOT NULL, " +
                "`reportCount` INTEGER NOT NULL, `listVersion` INTEGER NOT NULL, PRIMARY KEY(`e164`))",
        )

    /** Version 5 (Phase 11): the owner's video caller ID in `number_info`, and Flash messages, `flash_message`. */
    val MIGRATION_4_5: Migration =
        SqlMigration(
            4,
            5,
            "ALTER TABLE `number_info` ADD COLUMN `videoId` TEXT",
            "CREATE TABLE IF NOT EXISTS `flash_message` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `e164` TEXT NOT NULL, " +
                "`name` TEXT, `atMillis` INTEGER NOT NULL, `outgoing` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )

    /** Version 6 (Phase 13): calls the AI assistant answered, `screened_call` (on the phone only, ADR 0010). */
    val MIGRATION_5_6: Migration =
        SqlMigration(
            5,
            6,
            "CREATE TABLE IF NOT EXISTS `screened_call` (`id` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `e164` TEXT, " +
                "`name` TEXT, `reason` TEXT, `urgency` TEXT NOT NULL, `likelySpam` INTEGER NOT NULL, `transcript` TEXT NOT NULL, " +
                "`endReason` TEXT NOT NULL, PRIMARY KEY(`id`))",
            "CREATE INDEX IF NOT EXISTS `index_screened_call_startedAt` ON `screened_call` (`startedAt`)",
        )

    /** Every migration, in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
}

/**
 * A migration made of plain SQL statements. The app opens the database through SQLCipher's `SupportSQLite`
 * helper (the first overload); `MigrationTest` uses a driver (the second); both run the same statements.
 */
private class SqlMigration(
    from: Int,
    to: Int,
    vararg val statements: String,
) : Migration(from, to) {
    override fun migrate(db: SupportSQLiteDatabase) = statements.forEach(db::execSQL)

    override fun migrate(connection: SQLiteConnection) = statements.forEach(connection::execSQL)
}
