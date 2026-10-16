package io.github.jeshiomurmu.opencaller.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
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

    @Test
    fun exportedVersion1SchemaMatchesTheEntities() =
        runTest {
            helper.createDatabase(1).close()
            helper.runMigrationsAndValidate(1, emptyList()).close()
        }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
