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

    /** Every migration, in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
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
