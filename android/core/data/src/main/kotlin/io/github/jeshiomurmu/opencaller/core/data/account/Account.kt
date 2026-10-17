package io.github.jeshiomurmu.opencaller.core.data.account

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import kotlinx.coroutines.flow.Flow

/** Whether online features can be used: a server in this build, and the user signed in. */
interface AccountStatus {
    /** True while the user is signed in. */
    val signedIn: Flow<Boolean>

    /** True when this build has a server to talk to. */
    val serverConfigured: Boolean

    /** The server's "unlist my number" page (works without the app), or `null` without a server. */
    val unlistPageUrl: String?
}

/** Optional sign-in with the user's phone number. */
interface Account : AccountStatus {
    /** Sends a sign-in code to [number]; returns how many seconds the code is valid. */
    suspend fun sendCode(number: PhoneNumber): AppResult<Long>

    /** Checks [code] for [number] and, if right, stores the tokens and sends any queued reports. */
    suspend fun verify(
        number: PhoneNumber,
        code: String,
    ): AppResult<Unit>

    /** Signs out on this phone (the tokens are deleted; queued reports wait for the next sign-in). */
    suspend fun signOut()
}
