package io.github.jeshiomurmu.opencaller.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration test harness. Every schema change adds a `MIGRATION_n_m` and a test here that creates version n
 * from the exported schema, migrates and validates (docs/architecture.md §6).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @get:Rule
    val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = instrumentation.targetContext.getDatabasePath(TEST_DB),
            driver = AndroidSQLiteDriver(),
            databaseClass = OpenCallerDatabase::class,
        )

    // The helper doesn't delete the file between tests, and creating version 1 over an existing one fails.
    @Before
    fun deleteTestDatabase() {
        instrumentation.targetContext.deleteDatabase(TEST_DB)
    }

    @Test
    fun exportedVersion1SchemaIsValid() =
        runTest {
            helper.createDatabase(1).close()
            helper.runMigrationsAndValidate(1, emptyList()).close()
        }

    // Version 2 (Phase 8): number_info gains `badge`; cached rows keep their data and have no badge.
    @Test
    fun migration1To2AddsTheBadgeAndKeepsTheCache() =
        runTest {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    "INSERT INTO number_info (e164, displayName, nameSource, spamScore, spamCategory, reportCount, " +
                        "isBusiness, businessId, verified, logoUrl, callReason, lineType, fetchedAt, expiresAt) " +
                        "VALUES ('+12015550123', 'Asha', 'SELF_REGISTERED', NULL, NULL, 0, 0, NULL, 0, NULL, NULL, NULL, 1, 2)",
                )
            }
            helper.runMigrationsAndValidate(2, listOf(Migrations.MIGRATION_1_2)).use { db ->
                db.prepare("SELECT displayName, badge FROM number_info WHERE e164 = '+12015550123'").use { row ->
                    assertTrue(row.step())
                    assertEquals("Asha", row.getText(0))
                    assertTrue(row.isNull(1))
                }
            }
        }

    // Version 3 (Phase 9): an empty verified_business table; earlier tables keep their rows.
    @Test
    fun migration2To3AddsTheBusinessDirectory() =
        runTest {
            helper.createDatabase(2).use { db ->
                db.execSQL(
                    "INSERT INTO number_info (e164, displayName, nameSource, spamScore, spamCategory, reportCount, " +
                        "isBusiness, businessId, verified, logoUrl, callReason, lineType, fetchedAt, expiresAt, badge) " +
                        "VALUES ('+12015550123', 'Asha', 'SELF_REGISTERED', NULL, NULL, 0, 0, NULL, 0, NULL, NULL, NULL, 1, 2, 'GOLD')",
                )
            }
            helper.runMigrationsAndValidate(3, listOf(Migrations.MIGRATION_2_3)).use { db ->
                db.prepare("SELECT badge FROM number_info WHERE e164 = '+12015550123'").use { row ->
                    assertTrue(row.step())
                    assertEquals("GOLD", row.getText(0))
                }
                db.execSQL(
                    "INSERT INTO verified_business (e164, businessId, name, category, callReason, logoPath, logoFile, updatedAt) " +
                        "VALUES ('+8801812345678', 'b1', 'Example Bank', 'bank', 'Card security', NULL, NULL, 5)",
                )
                db.prepare("SELECT name FROM verified_business WHERE e164 = '+8801812345678'").use { row ->
                    assertTrue(row.step())
                    assertEquals("Example Bank", row.getText(0))
                }
            }
        }

    // Version 4 (Phase 10): an empty spam_list_extended table; the standard spam list keeps its rows.
    @Test
    fun migration3To4AddsTheExtendedSpamList() =
        runTest {
            helper.createDatabase(3).use { db ->
                db.execSQL(
                    "INSERT INTO spam_list (e164, score, category, reportCount, listVersion) VALUES ('+12015550123', 0.9, 'scam', 20, 3)",
                )
            }
            helper.runMigrationsAndValidate(4, listOf(Migrations.MIGRATION_3_4)).use { db ->
                db.prepare("SELECT reportCount FROM spam_list WHERE e164 = '+12015550123'").use { row ->
                    assertTrue(row.step())
                    assertEquals(20L, row.getLong(0))
                }
                db.execSQL(
                    "INSERT INTO spam_list_extended (e164, score, category, reportCount, listVersion) " +
                        "VALUES ('+12015550124', 0.5, '', 1, 3)",
                )
                db.prepare("SELECT COUNT(*) FROM spam_list_extended").use { row ->
                    assertTrue(row.step())
                    assertEquals(1L, row.getLong(0))
                }
            }
        }

    // Version 5 (Phase 11): number_info keeps its rows and gains videoId; an empty flash_message table.
    @Test
    fun migration4To5AddsVideoCallerIdsAndFlashMessages() =
        runTest {
            helper.createDatabase(4).use { db ->
                db.execSQL(
                    "INSERT INTO number_info (e164, displayName, nameSource, spamScore, spamCategory, reportCount, " +
                        "isBusiness, businessId, verified, logoUrl, callReason, lineType, fetchedAt, expiresAt, badge) " +
                        "VALUES ('+12015550123', 'Asha', 'SELF_REGISTERED', NULL, NULL, 0, 0, NULL, 0, NULL, NULL, NULL, 1, 2, NULL)",
                )
            }
            helper.runMigrationsAndValidate(5, listOf(Migrations.MIGRATION_4_5)).use { db ->
                db.prepare("SELECT displayName, videoId FROM number_info WHERE e164 = '+12015550123'").use { row ->
                    assertTrue(row.step())
                    assertEquals("Asha", row.getText(0))
                    assertTrue(row.isNull(1))
                }
                db.execSQL(
                    "INSERT INTO flash_message (id, kind, e164, name, atMillis, outgoing) VALUES ('f1', 'call_me', '+12015550123', NULL, 5, 0)",
                )
                db.prepare("SELECT COUNT(*) FROM flash_message").use { row ->
                    assertTrue(row.step())
                    assertEquals(1L, row.getLong(0))
                }
            }
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
