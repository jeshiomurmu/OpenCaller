package io.github.jeshiomurmu.opencaller.core.database

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.execSQL

/** Schema migrations of [OpenCallerDatabase], each tested in `MigrationTest` (docs/architecture.md §6). */
object Migrations {
    /** Version 2 (Phase 8): the supporter badge in the reputation cache, `number_info.badge`. */
    val MIGRATION_1_2: Migration = SqlMigration(1, 2, "ALTER TABLE `number_info` ADD COLUMN `badge` TEXT")

    /** Every migration, in order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
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
