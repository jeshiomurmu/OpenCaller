package io.github.jeshiomurmu.opencaller.qa

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningTestHooks
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.Properties
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: what the emulator can't fake for the QA script — the SIM's country (the emulator's SIM is US)
 * and verified businesses (none exist before Phase 9). Set through [QaCommandReceiver]; kept in a small file so the
 * values survive the app process being restarted between calls.
 */
@Singleton
class QaHooks
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ScreeningTestHooks {
        private val file = File(context.filesDir, FILE)

        override fun homeRegionOverride(): String? = read().getProperty(KEY_REGION)

        override fun isVerifiedBusiness(number: PhoneNumber): Boolean = verified(read()).contains(number.e164)

        /** Uses [region] as the SIM country, or the real one again when `null`. */
        fun setRegion(region: String?) =
            change { props -> if (region == null) props.remove(KEY_REGION) else props.setProperty(KEY_REGION, region) }

        /** Treats [number] as a verified business. */
        fun addVerifiedBusiness(number: PhoneNumber) =
            change { props -> props.setProperty(KEY_VERIFIED, (verified(props) + number.e164).joinToString(",")) }

        /** Forgets every override. */
        @Synchronized
        fun clear() {
            file.delete()
        }

        private fun verified(props: Properties): Set<String> =
            props
                .getProperty(KEY_VERIFIED)
                .orEmpty()
                .split(',')
                .filter { it.isNotEmpty() }
                .toSet()

        @Synchronized
        private fun read(): Properties =
            Properties().apply {
                try {
                    if (file.exists()) file.inputStream().use(::load)
                } catch (e: IOException) {
                    Timber.w(e, "QA hooks unreadable")
                }
            }

        @Synchronized
        private fun change(edit: (Properties) -> Unit) {
            val props = read().also(edit)
            try {
                file.outputStream().use { props.store(it, null) }
            } catch (e: IOException) {
                Timber.w(e, "QA hooks not saved")
            }
        }

        private companion object {
            const val FILE = "qa_hooks.properties"
            const val KEY_REGION = "region"
            const val KEY_VERIFIED = "verified"
        }
    }
