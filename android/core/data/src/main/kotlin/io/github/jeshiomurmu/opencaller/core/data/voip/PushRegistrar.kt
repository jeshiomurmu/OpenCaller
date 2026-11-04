package io.github.jeshiomurmu.opencaller.core.data.voip

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.account.AccountStatus
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import io.github.jeshiomurmu.opencaller.core.model.ServerFeatures
import io.github.jeshiomurmu.opencaller.core.network.BillingApi
import io.github.jeshiomurmu.opencaller.core.network.PushRegistrationDto
import io.github.jeshiomurmu.opencaller.core.network.VoipApi
import io.github.jeshiomurmu.opencaller.core.network.apiCall
import io.github.jeshiomurmu.opencaller.core.network.apiCallNoContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This device's push endpoint on the server (ADR 0007). The push provider of the build (FCM in play, a UnifiedPush
 * distributor in foss) hands the endpoint to [onNewEndpoint]; it is kept on the phone and sent whenever the user
 * is signed in, and sent again after a sign-in ([resend]).
 */
@Singleton
class PushRegistrar
    @Inject
    constructor(
        private val voipApi: VoipApi,
        private val billingApi: BillingApi,
        private val account: AccountStatus,
        private val preferences: VoipPreferences,
    ) {
        /** The server's features as last fetched. */
        val features: Flow<ServerFeatures> = preferences.features

        /** Fetches the server's features (app calls, Flash, the VAPID key) and remembers them. */
        suspend fun refreshFeatures(): ServerFeatures? {
            val features = apiCall { billingApi.serverInfo() }.getOrNull()?.features?.toModel() ?: return null
            preferences.setFeatures(features)
            return features
        }

        /** A push provider gave this device [endpoint]: keep it and send it. */
        suspend fun onNewEndpoint(endpoint: PushEndpoint): AppResult<Unit> {
            preferences.setEndpoint(endpoint)
            return send(endpoint)
        }

        /** The push provider dropped this device: forget the endpoint here and on the server. */
        suspend fun onEndpointGone(): AppResult<Unit> {
            preferences.setEndpoint(null)
            return if (account.signedIn.first()) apiCallNoContent { voipApi.unregisterPush() } else AppResult.Success(Unit)
        }

        /** Sends the kept endpoint again (after a sign-in, or when the app starts). */
        suspend fun resend(): AppResult<Unit> = preferences.endpoint()?.let { send(it) } ?: AppResult.Success(Unit)

        private suspend fun send(endpoint: PushEndpoint): AppResult<Unit> {
            if (!account.signedIn.first()) return AppResult.Success(Unit)
            val body =
                when (endpoint) {
                    is PushEndpoint.Fcm -> PushRegistrationDto(type = "fcm", token = endpoint.token)
                    is PushEndpoint.WebPush -> PushRegistrationDto("webpush", endpoint = endpoint.url, p256dh = endpoint.p256dh, auth = endpoint.auth)
                }
            val result = apiCallNoContent { voipApi.registerPush(body) }
            if (result is AppResult.Failure && result.error !is AppError.Network) Timber.w("Push endpoint refused: %s", result.error)
            return result
        }
    }
