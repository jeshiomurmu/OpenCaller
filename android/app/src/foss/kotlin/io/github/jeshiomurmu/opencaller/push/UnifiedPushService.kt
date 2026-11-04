package io.github.jeshiomurmu.opencaller.push

import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.data.voip.NotificationRouter
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushMessage
import timber.log.Timber
import javax.inject.Inject
import org.unifiedpush.android.connector.data.PushEndpoint as DistributorEndpoint

/**
 * Receives UnifiedPush events. A message is the server's Web Push body, already decrypted by the connector:
 * `{"id":"…"}`, a random push-item id the phone fetches from the server.
 */
@AndroidEntryPoint
class UnifiedPushService : PushService() {
    @Inject
    lateinit var registrar: PushRegistrar

    @Inject
    lateinit var router: NotificationRouter

    @Inject
    lateinit var startup: VoipStartup

    override fun onNewEndpoint(
        endpoint: DistributorEndpoint,
        instance: String,
    ) {
        val keys = endpoint.pubKeySet ?: return Timber.w("UnifiedPush endpoint without Web Push keys")
        startup.scope.launch { registrar.onNewEndpoint(PushEndpoint.WebPush(endpoint.url, keys.pubKey, keys.auth)) }
    }

    override fun onMessage(
        message: PushMessage,
        instance: String,
    ) {
        if (!message.decrypted) return Timber.w("UnifiedPush message not decrypted")
        val id = ITEM_ID.find(message.content.decodeToString())?.groupValues?.get(1) ?: return
        startup.scope.launch { router.onPush(id) }
    }

    override fun onRegistrationFailed(
        reason: FailedReason,
        instance: String,
    ) {
        Timber.w("UnifiedPush registration failed: %s", reason)
    }

    override fun onUnregistered(instance: String) {
        startup.scope.launch { registrar.onEndpointGone() }
    }

    private companion object {
        val ITEM_ID = Regex(""""id"\s*:\s*"([0-9a-fA-F-]{36})"""")
    }
}
