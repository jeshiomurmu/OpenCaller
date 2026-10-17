package io.github.jeshiomurmu.opencaller.core.data.account

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.work.BackgroundWork
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.network.OpenCallerApi
import io.github.jeshiomurmu.opencaller.core.network.ServerConfig
import io.github.jeshiomurmu.opencaller.core.network.StartRequest
import io.github.jeshiomurmu.opencaller.core.network.VerifyRequest
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.toAuthTokens
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional sign-in with the user's phone number (docs/architecture.md §4.7): a code by SMS, then tokens. Blocking
 * works without it; signing in lets the app send reports and search.
 */
@Singleton
class AccountRepository
    @Inject
    constructor(
        private val api: OpenCallerApi,
        private val tokens: KeystoreTokenStore,
        private val server: ServerConfig,
        private val clock: Clock,
        private val work: BackgroundWork,
    ) {
        /** True while the user is signed in. */
        val signedIn: Flow<Boolean> = tokens.signedIn

        /** True when this build has a server to sign in to. */
        val serverConfigured: Boolean get() = server.isConfigured

        /** Sends a sign-in code to [number]; returns how many seconds the code is valid. */
        suspend fun sendCode(number: PhoneNumber): AppResult<Long> =
            apiCall { api.startSignIn(StartRequest(number.e164)) }.map { it.expiresInSeconds }

        /** Checks [code] for [number] and, if right, stores the tokens and sends any queued reports. */
        suspend fun verify(
            number: PhoneNumber,
            code: String,
        ): AppResult<Unit> =
            apiCall { api.verify(VerifyRequest(number.e164, code, server.flavor)) }.map { response ->
                tokens.write(response.toAuthTokens(clock.nowMillis()))
                work.uploadReports()
            }

        /** Signs out on this phone (the tokens are deleted; queued reports wait for the next sign-in). */
        suspend fun signOut() = tokens.clear()
    }
