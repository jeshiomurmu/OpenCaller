package io.github.jeshiomurmu.opencaller.feature.callerid.announce

import android.app.NotificationManager
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.supporter.EntitlementProvider
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Reads [AudioConditions]. */
fun interface AudioConditionsReader {
    /** The current state. */
    fun read(): AudioConditions
}

/** Speaks a sentence (TextToSpeech on the phone, a recorder in tests). */
interface Speaker {
    /** Speaks [text]; a newer call replaces whatever is still being said. */
    fun speak(text: String)

    /** Stops speaking (the call was answered or ended). */
    fun stop()
}

/**
 * Announce Caller (docs/prd.md F-UT-02): when a screened call rings, speaks the caller's name in the phone's
 * language if the user is a supporter, chose to hear it (always, or with headphones only) and the phone isn't
 * silenced or in Do Not Disturb. Blocked and silenced calls are never announced. Nothing leaves the phone.
 */
@Singleton
class CallerAnnouncer
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val settings: UserSettingsDataSource,
        private val entitlements: EntitlementProvider,
        private val audio: AudioConditionsReader,
        private val speaker: Speaker,
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.main + CoroutineExceptionHandler { _, e -> Timber.w(e, "Announce Caller failed") })

        /** Announces [caller] if the settings and the phone's state allow it. */
        fun announce(caller: CallerInfo) {
            scope.launch {
                val mode = settings.preferences.first().announceCallerMode
                if (!AnnouncePolicy.shouldAnnounce(mode, entitlements.current().entitled, audio.read())) return@launch
                val announcement = AnnouncePolicy.announcementFor(caller)
                val text =
                    announcement.name?.let { context.getString(announcement.text, it) } ?: context.getString(announcement.text)
                speaker.speak(text)
            }
        }

        /** Stops speaking. */
        fun stop() = speaker.stop()
    }

/** [AudioConditions] from [AudioManager] and [NotificationManager]. */
class DeviceAudioConditions
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : AudioConditionsReader {
        override fun read(): AudioConditions {
            val audio = context.getSystemService(AudioManager::class.java)
            val notifications = context.getSystemService(NotificationManager::class.java)
            val outputs = audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
            return AudioConditions(
                headphones = outputs.any { it.type in HEADPHONE_TYPES },
                ringerAudible = audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                interruptionsAllowed = notifications?.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL,
            )
        }

        private companion object {
            // Output types that are headphones (all available on API 29 except the BLE headset, API 31).
            val HEADPHONE_TYPES =
                setOf(
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    BLE_HEADSET,
                )

            // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31); the number never matches anything on older versions.
            const val BLE_HEADSET = 26
        }
    }
