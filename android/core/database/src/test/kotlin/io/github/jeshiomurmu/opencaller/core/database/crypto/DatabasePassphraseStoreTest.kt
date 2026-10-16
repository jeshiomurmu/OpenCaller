package io.github.jeshiomurmu.opencaller.core.database.crypto

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DatabasePassphraseStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Reversible stand-in for the Keystore cipher; marks its output so tests can tell it was applied. */
    private class FakeCipher : PassphraseCipher {
        var encryptions = 0

        override fun encrypt(plaintext: ByteArray): ByteArray {
            encryptions++
            return byteArrayOf(MARK) + plaintext.map { (it.toInt() xor MASK).toByte() }
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            check(ciphertext[0] == MARK)
            return ciphertext.drop(1).map { (it.toInt() xor MASK).toByte() }.toByteArray()
        }

        companion object {
            const val MARK: Byte = 0x7A
            const val MASK = 0x5C
        }
    }

    @Test
    fun createsHexPassphraseOnceAndReusesIt() =
        runTest {
            val file = File(tmp.root, "database_key.preferences_pb")
            val cipher = FakeCipher()
            val store = DatabasePassphraseStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file }, cipher)

            val first = store.getOrCreate()
            val second = store.getOrCreate()

            assertEquals(DatabasePassphraseStore.PASSPHRASE_BYTES * 2, first.size)
            assertTrue(first.all { it.toInt().toChar() in "0123456789abcdef" })
            assertArrayEquals(first, second)
            assertEquals(1, cipher.encryptions)
            // Only the wrapped form is written to disk.
            assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains(first.toString(Charsets.US_ASCII)))
        }

    @Test
    fun differentStoresGenerateDifferentPassphrases() =
        runTest {
            val a =
                DatabasePassphraseStore(
                    PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "a.preferences_pb") },
                    FakeCipher(),
                )
            val b =
                DatabasePassphraseStore(
                    PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "b.preferences_pb") },
                    FakeCipher(),
                )
            assertFalse(a.getOrCreate().contentEquals(b.getOrCreate()))
        }
}
