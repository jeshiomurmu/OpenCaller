package io.github.jeshiomurmu.opencaller.core.network

/**
 * The user's tokens. Times are epoch milliseconds.
 *
 * @property accessExpiresAt when [accessToken] stops working (15 minutes after issue).
 * @property refreshExpiresAt when [refreshToken] stops working (30 days, renewed by every refresh).
 */
data class AuthTokens(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
) {
    /** Never prints the tokens. */
    override fun toString(): String = "AuthTokens(accessExpiresAt=$accessExpiresAt, refreshExpiresAt=$refreshExpiresAt)"
}

/**
 * Where the tokens live. The app stores them encrypted with an Android Keystore AES-GCM key
 * (docs/architecture.md §4.7); tests use memory.
 */
interface TokenStore {
    /** The stored tokens, or `null` when signed out. */
    suspend fun read(): AuthTokens?

    /** Replaces the stored tokens. */
    suspend fun write(tokens: AuthTokens)

    /** Signs out locally: deletes the tokens. */
    suspend fun clear()
}
