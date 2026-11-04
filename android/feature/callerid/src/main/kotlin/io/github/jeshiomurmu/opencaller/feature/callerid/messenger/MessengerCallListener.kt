package io.github.jeshiomurmu.opencaller.feature.callerid.messenger

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Notification access for messenger caller ID (docs/architecture.md §5: "Notification listener access, when the
 * feature is enabled"). Every other app's notifications are ignored at once: only a supported messenger's call
 * notification is looked at, and only its texts, which are never stored or sent anywhere.
 */
@AndroidEntryPoint
class MessengerCallListener : NotificationListenerService() {
    @Inject
    lateinit var callerId: MessengerCallerId

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        if (MessengerApp.of(posted.packageName) == null) return
        callerId.onPosted(posted.key, posted.toPosted())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val removed = sbn ?: return
        if (MessengerApp.of(removed.packageName) == null) return
        callerId.onRemoved(removed.key)
    }
}
