package io.github.jeshiomurmu.opencaller.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.data.voip.NotificationRouter
import io.github.jeshiomurmu.opencaller.core.data.voip.PushRegistrar
import io.github.jeshiomurmu.opencaller.core.model.PushEndpoint
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Receives FCM messages. A message carries only `id`, a random push-item id; the phone fetches what it stands for
 * (a call invite, a call update or a Flash message) from the server. Firebase calls these on a background thread
 * and keeps the process alive until they return, so they wait for the fetch (high-priority messages also let the
 * call service start in the foreground).
 */
@AndroidEntryPoint
class FcmService : FirebaseMessagingService() {
    @Inject
    lateinit var registrar: PushRegistrar

    @Inject
    lateinit var router: NotificationRouter

    // Deprecated in firebase-messaging 26 (the replacement couldn't be checked from the agent sandbox, docs/phase.md
    // issue 137); Firebase still calls it when the token changes.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        runBlocking { withTimeoutOrNull(TIMEOUT_MILLIS) { registrar.onNewEndpoint(PushEndpoint.Fcm(token)) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val id = message.data["id"] ?: return
        runBlocking { withTimeoutOrNull(TIMEOUT_MILLIS) { router.onPush(id) } }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 15_000L
    }
}
