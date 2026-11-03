package io.github.jeshiomurmu.opencaller.feature.callerid.messenger

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.StatusBarNotification
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.NotificationListenerAccess
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.feature.callerid.CallerCardFormatter
import io.github.jeshiomurmu.opencaller.feature.callerid.CallerCardModel
import io.github.jeshiomurmu.opencaller.feature.callerid.CallerIdSurfaces
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caller ID for WhatsApp, Telegram and Signal calls (docs/prd.md F-ID-05): when a supporter turned it on and one
 * of these apps posts a call notification with a phone number in its text, the caller card shows what the phone
 * knows about that number (contacts, spam list, verified businesses; never the network). Without a number nothing
 * is shown. The card closes when the app removes its notification.
 */
@Singleton
class MessengerCallerId
    @Inject
    constructor(
        private val settings: UserSettingsDataSource,
        private val entitlements: EntitlementProvider,
        private val cards: MessengerCallerCards,
        private val surfaces: CallerIdSurfaces,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(
                SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> Timber.w(e, "Messenger caller ID failed") },
            )
        private var showingKey: String? = null

        /** A notification was posted. */
        fun onPosted(
            key: String,
            notification: PostedNotification,
        ) {
            if (!MessengerCalls.isMessengerCall(notification)) return
            scope.launch {
                if (!settings.preferences.first().messengerCallerId || !entitlements.current().entitled) return@launch
                val card = cards.cardFor(notification) ?: return@launch
                // The overlay when the user allowed it (it falls back to the notification if Android refuses the window).
                val overlayAllowed = settings.preferences.first().overlayEnabled
                val shown = (overlayAllowed && surfaces.overlay.show(card) { dismiss(key) }) || surfaces.notifications.showCallerId(card)
                if (shown) showingKey = key
            }
        }

        /** A notification was removed. */
        fun onRemoved(key: String) {
            if (key == showingKey) dismiss(key)
        }

        private fun dismiss(key: String) {
            if (key != showingKey) return
            showingKey = null
            surfaces.overlay.dismiss()
            surfaces.notifications.cancelCallerId()
        }
    }

/**
 * The caller card for a messenger's call notification: the number in its text (read in the phone's home region),
 * described from what the phone knows (never the network); `null` when the text has no number.
 */
class MessengerCallerCards
    @Inject
    constructor(
        private val normalizer: PhoneNumberNormalizer,
        private val homeRegion: HomeRegionProvider,
        private val lookup: CallerLookup,
        private val formatter: CallerCardFormatter,
    ) {
        /** The card for [notification], or `null`. */
        suspend fun cardFor(notification: PostedNotification): CallerCardModel? =
            MessengerCalls
                .numberIn(notification, homeRegion.homeRegion() ?: UNKNOWN_REGION, normalizer)
                ?.let { formatter.card(lookup.describe(it.e164)) }

        private companion object {
            const val UNKNOWN_REGION = "ZZ"
        }
    }

/** The parts of [this] notification [MessengerCalls] looks at. */
internal fun StatusBarNotification.toPosted(): PostedNotification {
    val extras = notification.extras
    val texts =
        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_BIG_TEXT)
            .mapNotNull { extras?.getCharSequence(it)?.toString() }
            .filter { it.isNotBlank() }
    return PostedNotification(packageName, notification.category, extras?.getString(Notification.EXTRA_TEMPLATE), texts)
}

/** Whether OpenCaller may read notifications ([MessengerCallListener] is enabled in system settings). */
class DeviceNotificationListenerAccess
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : NotificationListenerAccess {
        override fun granted(): Boolean =
            context.getSystemService(NotificationManager::class.java)?.isNotificationListenerAccessGranted(
                ComponentName(context, MessengerCallListener::class.java),
            ) == true

        override fun settingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }
