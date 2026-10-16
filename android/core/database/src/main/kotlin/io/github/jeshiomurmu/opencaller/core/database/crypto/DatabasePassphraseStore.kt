package io.github.jeshiomurmu.opencaller.core.database.crypto

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.SecureRandom
import java.util.Base64

/**
 * The database passphrase: 32 random bytes generated once (hex-encoded for SQLCipher), stored in [dataStore]
 * only in encrypted form ([cipher], an Android Keystore AES-GCM key in production).
 */
class DatabasePassphraseStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: PassphraseCipher,
    private val random: SecureRandom = SecureRandom(),
) {
    private val mutex = Mutex()

    /** Returns the passphrase, creating and storing it on first call. */
    suspend fun getOrCreate(): ByteArray =
        mutex.withLock {
            val stored = dataStore.data.first()[WRAPPED_PASSPHRASE]
            if (stored != null) {
                cipher.decrypt(Base64.getDecoder().decode(stored))
            } else {
                val passphrase = newPassphrase()
                val wrapped = Base64.getEncoder().encodeToString(cipher.encrypt(passphrase))
                dataStore.edit { it[WRAPPED_PASSPHRASE] = wrapped }
                passphrase
            }
        }

    private fun newPassphrase(): ByteArray {
        val bytes = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        val hex = ByteArray(bytes.size * 2)
        bytes.forEachIndexed { i, byte ->
            hex[2 * i] = HEX_DIGITS[(byte.toInt() shr HALF_BYTE_BITS) and LOW_NIBBLE]
            hex[2 * i + 1] = HEX_DIGITS[byte.toInt() and LOW_NIBBLE]
        }
        return hex
    }

    /** Storage constants. */
    companion object {
        /** Random bytes in a passphrase (encoded as twice as many hex characters). */
        const val PASSPHRASE_BYTES = 32

        private val WRAPPED_PASSPHRASE = stringPreferencesKey("wrapped_database_passphrase")
        private val HEX_DIGITS = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        private const val HALF_BYTE_BITS = 4
        private const val LOW_NIBBLE = 0xF
    }
}
