package io.github.jeshiomurmu.opencaller.core.telephony.screening.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.business.BusinessDirectory
import io.github.jeshiomurmu.opencaller.core.data.sync.CountrySource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.VerifiedBusiness
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import io.github.jeshiomurmu.opencaller.core.telephony.screening.EmergencyNumbers
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningSourceException
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningTestHooks
import io.github.jeshiomurmu.opencaller.core.telephony.screening.VerifiedBusinessLookup
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale
import java.util.Optional
import javax.inject.Inject

/**
 * Looks callers up in the user's contacts with `ContactsContract.PhoneLookup`. The name is used on the device only:
 * it goes to the caller card and is never logged or sent anywhere (docs/architecture.md §10).
 */
class AndroidContactLookup
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : ContactLookup {
        override fun canRead(): Boolean =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

        override suspend fun displayName(number: PhoneNumber): String? {
            if (!canRead()) return null
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number.e164))
            return withContext(dispatchers.io) {
                try {
                    context.contentResolver
                        .query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                } catch (e: SecurityException) {
                    // The permission was revoked between the check and the query: treat the caller as a non-contact.
                    Timber.w(e, "Contacts lookup not allowed")
                    null
                }
            }
        }
    }

/**
 * The user's country: the SIM's, else the network's, else the device locale's. Debug builds can override it for
 * the emulator QA script ([ScreeningTestHooks]).
 */
class DeviceHomeRegionProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val testHooks: Optional<ScreeningTestHooks>,
    ) : HomeRegionProvider,
        CountrySource {
        override fun country(): String? = homeRegion()

        override fun homeRegion(): String? {
            val telephony = context.getSystemService(TelephonyManager::class.java)
            return listOf(
                { testHooks.orElse(null)?.homeRegionOverride() },
                { telephony?.simCountryIsoOrNull() },
                { telephony?.networkCountryIsoOrNull() },
                { Locale.getDefault().country },
            ).firstNotNullOfOrNull { read -> read()?.trim()?.uppercase()?.takeIf { it.length == 2 } }
        }

        private fun TelephonyManager.simCountryIsoOrNull(): String? = readOrNull("SIM country") { simCountryIso }

        private fun TelephonyManager.networkCountryIsoOrNull(): String? = readOrNull("network country") { networkCountryIso }

        // Devices without telephony throw UnsupportedOperationException here.
        private fun readOrNull(
            what: String,
            read: () -> String?,
        ): String? =
            try {
                read()
            } catch (e: UnsupportedOperationException) {
                Timber.w(e, "Can't read the %s", what)
                null
            }
    }

/**
 * Emergency numbers from `TelephonyManager.isEmergencyNumber` (API 29, no permission). If Android can't answer,
 * the number is treated as an emergency number, so the call is left alone.
 */
class TelephonyEmergencyNumbers
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : EmergencyNumbers {
        override fun isEmergency(handle: String): Boolean =
            try {
                context.getSystemService(TelephonyManager::class.java)?.isEmergencyNumber(handle) ?: false
            } catch (e: IllegalStateException) {
                Timber.w(e, "Emergency number check failed; leaving the call alone")
                true
            } catch (e: UnsupportedOperationException) {
                Timber.w(e, "Emergency number check unsupported; leaving the call alone")
                true
            }
    }

/**
 * Verified businesses from the directory the daily update keeps on the phone ([BusinessDirectory]), plus
 * the numbers the debug QA script marks ([ScreeningTestHooks], debug builds only). A storage failure throws
 * [ScreeningSourceException], so the engine lets the call ring instead of treating a business as a stranger.
 */
class LocalVerifiedBusinessLookup
    @Inject
    constructor(
        private val directory: BusinessDirectory,
        private val testHooks: Optional<ScreeningTestHooks>,
    ) : VerifiedBusinessLookup {
        override suspend fun find(number: PhoneNumber): VerifiedBusiness? =
            when (val found = directory.find(number)) {
                is AppResult.Success -> found.value ?: qaBusiness(number)
                is AppResult.Failure -> throw ScreeningSourceException(found.error)
            }

        private fun qaBusiness(number: PhoneNumber): VerifiedBusiness? =
            testHooks.orElse(null)?.takeIf { it.isVerifiedBusiness(number) }?.let {
                VerifiedBusiness(number, QA_BUSINESS_ID, name = QA_BUSINESS_NAME, category = null, callReason = null, logoPath = null)
            }

        private companion object {
            // Debug-only QA data (the hooks are never bound in release builds).
            const val QA_BUSINESS_ID = "qa"
            const val QA_BUSINESS_NAME = "QA business"
        }
    }
