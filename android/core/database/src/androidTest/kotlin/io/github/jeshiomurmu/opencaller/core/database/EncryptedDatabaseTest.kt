package io.github.jeshiomurmu.opencaller.core.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jeshiomurmu.opencaller.core.database.entity.BlockRuleEntity
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 1 exit criterion: the database file on disk is encrypted. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val passphrase = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".toByteArray()
    private val rule =
        BlockRuleEntity(
            type = RuleType.PREFIX,
            pattern = "+1900",
            action = RuleAction.BLOCK,
            appliesTo = RuleTarget.BOTH,
            createdAt = 1L,
            enabled = true,
        )

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(NAME)
    }

    @Test
    fun fileHeaderIsNotPlainSqlite() =
        runTest {
            val db = OpenCallerDatabaseFactory.create(context, passphrase, NAME)
            db.blockRuleDao().insert(rule)
            db.close()

            val header = ByteArray(PLAIN_SQLITE_HEADER.size)
            context.getDatabasePath(NAME).inputStream().use { input ->
                assertEquals(header.size, input.read(header))
            }
            assertFalse("database file starts with the plain SQLite header", header.contentEquals(PLAIN_SQLITE_HEADER))
        }

    @Test
    fun reopensWithTheSamePassphrase() =
        runTest {
            OpenCallerDatabaseFactory.create(context, passphrase, NAME).run {
                blockRuleDao().insert(rule)
                close()
            }
            val reopened = OpenCallerDatabaseFactory.create(context, passphrase, NAME)
            assertEquals(listOf("+1900"), reopened.blockRuleDao().enabledRules().map { it.pattern })
            reopened.close()
        }

    @Test
    fun cannotBeOpenedWithoutTheRightPassphrase() =
        runTest {
            OpenCallerDatabaseFactory.create(context, passphrase, NAME).run {
                blockRuleDao().insert(rule)
                close()
            }
            val path = context.getDatabasePath(NAME).path
            val plainOpen = runCatching { SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY).use { it.version } }
            assertTrue("plain SQLite could read the file", plainOpen.isFailure)

            val wrong = OpenCallerDatabaseFactory.create(context, "wrong".toByteArray(), NAME)
            assertTrue(runCatching { wrong.openHelper.writableDatabase }.isFailure)
            wrong.close()
        }

    private companion object {
        const val NAME = "encrypted-test.db"
        val PLAIN_SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
