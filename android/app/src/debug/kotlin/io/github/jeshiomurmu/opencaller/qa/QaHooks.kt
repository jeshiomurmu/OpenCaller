package io.github.jeshiomurmu.opencaller.qa

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningTestHooks
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: what the emulator can't fake for the QA script — the SIM's country (the emulator's SIM is US)
 * and verified businesses (none exist before Phase 9). Set through [QaCommandReceiver].
 */
@Singleton
class QaHooks
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : ScreeningTestHooks {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun homeRegionOverride(): String? = prefs.getString(KEY_REGION, null)

        override fun isVerifiedBusiness(number: PhoneNumber): Boolean =
            number.e164 in prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty()

        /** Uses [region] as the SIM country, or the real one again when `null`. */
        fun setRegion(region: String?) {
            prefs.edit().putString(KEY_REGION, region).apply()
        }

        /** Treats [number] as a verified business. */
        fun addVerifiedBusiness(number: PhoneNumber) {
            prefs.edit().putStringSet(KEY_VERIFIED, prefs.getStringSet(KEY_VERIFIED, emptySet()).orEmpty() + number.e164).apply()
        }

        /** Forgets every override. */
        fun clear() {
            prefs.edit().clear().apply()
        }

        private companion object {
            const val PREFS = "qa_hooks"
            const val KEY_REGION = "region"
            const val KEY_VERIFIED = "verified"
        }
    }
