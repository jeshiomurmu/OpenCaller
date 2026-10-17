package io.github.jeshiomurmu.opencaller.core.network

import io.github.jeshiomurmu.opencaller.core.common.Clock
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.IOException

/**
 * Adds `Authorization: Bearer <access token>` to requests marked with [AUTH_MODE_HEADER] when the user is signed in,
 * and removes the marker. Requests without the marker (sign-in, lists, files) never carry the token.
 */
class AuthInterceptor(
    private val tokens: TokenStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val mode = request.header(AUTH_MODE_HEADER) ?: return chain.proceed(request)
        val builder = request.newBuilder().removeHeader(AUTH_MODE_HEADER)
        // OkHttp runs interceptors on its own threads, so waiting for the store here blocks nothing the app needs.
        val access = runBlocking { tokens.read() }?.accessToken
        if (access != null && (mode == AUTH_REQUIRED || mode == AUTH_OPTIONAL)) builder.header(AUTHORIZATION, "$BEARER$access")
        return chain.proceed(builder.build())
    }
}

/**
 * Answers a 401 on a request that carried an access token: refreshes the tokens once and retries with the new
 * access token. Refreshes are serialized (two at once with the same refresh token would count as reuse on the server
 * and sign the device out, docs/phase.md issue 43); a request that failed with a token another thread has already
 * replaced is retried with the current one. If the server refuses the refresh token, the tokens are deleted: the
 * user is signed out and the request fails with its 401.
 */
class TokenAuthenticator(
    private val tokens: TokenStore,
    private val authApi: AuthApi,
    private val clock: Clock,
) : Authenticator {
    private val lock = Any()

    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        val used = response.request.header(AUTHORIZATION)?.removePrefix(BEARER)
        // A second 401 right after a refresh means the new token is refused too; stop instead of looping.
        if (used == null || response.priorResponse != null) return null
        val fresh =
            synchronized(lock) {
                runBlocking { tokens.read() }?.let { current -> if (current.accessToken != used) current else refresh(current) }
            }
        return fresh?.let {
            response.request
                .newBuilder()
                .header(AUTHORIZATION, "$BEARER${it.accessToken}")
                .build()
        }
    }

    private fun refresh(current: AuthTokens): AuthTokens? {
        val result =
            try {
                authApi.refresh(RefreshRequest(current.refreshToken)).execute()
            } catch (e: IOException) {
                // Offline: keep the tokens and let this request fail; the next one tries again.
                NetworkLog.warn("Token refresh failed", e)
                return null
            }
        val body = result.body()
        return when {
            result.isSuccessful && body != null -> {
                body.toAuthTokens(clock.nowMillis()).also { runBlocking { tokens.write(it) } }
            }

            result.code() == HTTP_UNAUTHORIZED -> {
                // Unknown, expired or reused refresh token: this sign-in is over.
                runBlocking { tokens.clear() }
                null
            }

            else -> {
                null
            }
        }
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}

/** Tokens from a verify or refresh response received at [nowMillis]. */
fun TokenResponse.toAuthTokens(nowMillis: Long): AuthTokens =
    AuthTokens(
        accessToken = accessToken,
        accessExpiresAt = nowMillis + accessTokenExpiresIn * MILLIS_PER_SECOND,
        refreshToken = refreshToken,
        refreshExpiresAt = nowMillis + refreshTokenExpiresIn * MILLIS_PER_SECOND,
    )

private const val AUTHORIZATION = "Authorization"
private const val BEARER = "Bearer "
private const val MILLIS_PER_SECOND = 1_000L
