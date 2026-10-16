package io.github.jeshiomurmu.opencaller.core.database

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jeshiomurmu.opencaller.core.database.crypto.AndroidKeystoreCipher
import io.github.jeshiomurmu.opencaller.core.database.crypto.DatabasePassphraseStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCipherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val cipher = AndroidKeystoreCipher(alias = "opencaller.test.key")

    @Test
    fun roundTripsAndUsesAFreshIvEachTime() {
        val plaintext = "secret passphrase".toByteArray()
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertFalse(first.contentEquals(second))
        assertArrayEquals(plaintext, cipher.decrypt(first))
        assertArrayEquals(plaintext, cipher.decrypt(second))
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val blob = cipher.encrypt("secret".toByteArray())
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 1).toByte()
        assertTrue(runCatching { cipher.decrypt(blob) }.isFailure)
    }

    @Test
    fun passphraseSurvivesANewStoreInstance() =
        runTest {
            val file = File(context.cacheDir, "passphrase-test.preferences_pb").apply { delete() }
            val first = withStore(file) { it.getOrCreate() }
            val second = withStore(file) { it.getOrCreate() }

            assertEquals(DatabasePassphraseStore.PASSPHRASE_BYTES * 2, first.size)
            assertArrayEquals(first, second)
            file.delete()
        }

    /** Runs [block] with a store whose DataStore is fully closed afterwards (one active DataStore per file). */
    private suspend fun <T> withStore(
        file: File,
        block: suspend (DatabasePassphraseStore) -> T,
    ): T {
        val scope = CoroutineScope(Job())
        try {
            return block(DatabasePassphraseStore(PreferenceDataStoreFactory.create(scope = scope) { file }, cipher))
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
