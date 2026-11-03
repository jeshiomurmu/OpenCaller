package io.github.jeshiomurmu.opencaller.feature.callerid.messenger

import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer

/**
 * Messengers whose incoming-call notifications OpenCaller reads (docs/prd.md F-ID-05), by package. The package
 * names are the apps' Play Store IDs. Tested versions: none yet on a phone (docs/testing.md → TC-UT-004).
 */
enum class MessengerApp(
    val packages: Set<String>,
) {
    WHATSAPP(setOf("com.whatsapp", "com.whatsapp.w4b")),
    TELEGRAM(setOf("org.telegram.messenger")),
    SIGNAL(setOf("org.thoughtcrime.securesms")),
    ;

    /** Lookup by package. */
    companion object {
        /** The messenger behind [packageName], or `null` for any other app. */
        fun of(packageName: String): MessengerApp? = entries.firstOrNull { packageName in it.packages }
    }
}

/**
 * The parts of a posted notification this feature looks at: the app, whether Android marks it as a call
 * (`Notification.CATEGORY_CALL`, or the `CallStyle` template), and its visible texts (title, text, sub text, big
 * text). Nothing else is read, and nothing is kept.
 */
data class PostedNotification(
    val packageName: String,
    val category: String?,
    val template: String?,
    val texts: List<String>,
)

/** Which posted notifications are messenger calls, and the number in them. */
object MessengerCalls {
    /** `Notification.CATEGORY_CALL`. */
    const val CATEGORY_CALL = "call"

    /** The `android.template` extra of a `Notification.CallStyle` notification. */
    const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"

    /** True for an incoming or ongoing call notification of a supported messenger. */
    fun isMessengerCall(notification: PostedNotification): Boolean =
        MessengerApp.of(notification.packageName) != null &&
            (notification.category == CATEGORY_CALL || notification.template == CALL_STYLE_TEMPLATE)

    /**
     * The first valid phone number written in the notification's texts, read in [homeRegion]; `null` when there is
     * none (the caller is saved under a name, or the app shows no number). Then no caller ID is shown.
     */
    fun numberIn(
        notification: PostedNotification,
        homeRegion: String,
        normalizer: PhoneNumberNormalizer,
    ): PhoneNumber? = notification.texts.firstNotNullOfOrNull { text -> normalizer.findInText(text, homeRegion).firstOrNull() }
}
