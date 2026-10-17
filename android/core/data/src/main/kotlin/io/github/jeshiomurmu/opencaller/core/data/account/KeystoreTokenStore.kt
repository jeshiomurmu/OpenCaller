package io.github.jeshiomurmu.opencaller.core.data.account

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jeshiomurmu.opencaller.core.database.crypto.PassphraseCipher
import io.github.jeshiomurmu.opencaller.core.network.AuthTokens
import io.github.jeshiomurmu.opencaller.core.network.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.security.GeneralSecurityException
import java.util.Base64

/**
 * The sign-in tokens, encrypted with an Android Keystore AES-GCM key ([cipher]) and kept in [dataStore]
 * (docs/architecture.md §4.7; not the deprecated security-crypto library). Tokens that can no longer be decrypted
 * (e.g. the Keystore key was lost with a device restore) are deleted: the user signs in again.
 */
class KeystoreTokenStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: PassphraseCipher,
) : TokenStore {
    /** True while tokens are stored. */
    val signedIn: Flow<Boolean> = dataStore.data.map { it[TOKENS] != null }

    override suspend fun read(): AuthTokens? {
        val stored = dataStore.data.first()[TOKENS] ?: return null
        return try {
            decode(String(cipher.decrypt(Base64.getDecoder().decode(stored)), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            Timber.w(e, "Stored tokens could not be decrypted; signing out")
            clear()
            null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Stored tokens are malformed; signing out")
            clear()
            null
        }
    }

    override suspend fun write(tokens: AuthTokens) {
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(encode(tokens).toByteArray(Charsets.UTF_8)))
        dataStore.edit { it[TOKENS] = encrypted }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(TOKENS) }
    }

    /** Storage format. */
    companion object {
        /** Keystore alias of the key that encrypts the tokens. */
        const val KEY_ALIAS = "opencaller.auth.tokens"

        /** DataStore file name. */
        const val FILE_NAME = "auth_tokens"

        private val TOKENS = stringPreferencesKey("tokens")
        private const val FIELDS = 4
        private const val ACCESS = 0
        private const val ACCESS_EXPIRY = 1
        private const val REFRESH = 2
        private const val REFRESH_EXPIRY = 3

        // Tokens are JWTs and base64url strings: no line breaks, so one line per field is unambiguous.
        internal fun encode(tokens: AuthTokens): String =
            listOf(tokens.accessToken, tokens.accessExpiresAt, tokens.refreshToken, tokens.refreshExpiresAt).joinToString("\n")

        internal fun decode(text: String): AuthTokens {
            val parts = text.split('\n')
            require(parts.size == FIELDS) { "expected $FIELDS fields" }
            return AuthTokens(
                accessToken = parts[ACCESS],
                accessExpiresAt = requireNotNull(parts[ACCESS_EXPIRY].toLongOrNull()) { "bad expiry" },
                refreshToken = parts[REFRESH],
                refreshExpiresAt = requireNotNull(parts[REFRESH_EXPIRY].toLongOrNull()) { "bad expiry" },
            )
        }
    }
}
