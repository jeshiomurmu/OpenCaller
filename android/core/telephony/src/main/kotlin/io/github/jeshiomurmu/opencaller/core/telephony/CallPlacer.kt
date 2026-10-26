package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.telephony.screening.EmergencyNumbers
import timber.log.Timber
import javax.inject.Inject

/** Places calls from OpenCaller's dial pad, call log and contacts. */
interface CallPlacer {
    /** True when OpenCaller may place calls itself (the call permission, asked only of the default phone app). */
    fun canPlaceCalls(): Boolean

    /** True when [number] is an emergency number on this device (place it with [placeEmergency], at once). */
    fun isEmergencyNumber(number: String): Boolean

    /**
     * Places a call to [number] through Telecom (emergency numbers and carrier codes such as `*123#` included, as the
     * default phone app), on the SIM [simId] from [SimAccounts], or the default SIM when `null`.
     */
    fun place(
        number: String,
        simId: String? = null,
    ): AppResult<Unit>

    /**
     * Places an emergency call to [number] at once, without the call permission: `TelecomManager.placeCall` allows
     * emergency calls from the default phone app (developer.android.com), and nothing may delay them (TC-BLK-013).
     */
    fun placeEmergency(number: String): AppResult<Unit>

    /** Calls voicemail through Telecom (`voicemail:`), on [simId] or the default SIM. */
    fun placeVoicemail(simId: String? = null): AppResult<Unit>

    /** Hands a secret code `*#*#digits#*#*` to Android (`TelephonyManager.sendDialerSpecialCode`); it is never dialled. */
    fun sendSecretCode(digits: String): AppResult<Unit>

    /** Opens the system phone app's dial pad with [number] (OpenCaller isn't the default phone app). */
    fun handOff(number: String): AppResult<Unit>
}

/**
 * [CallPlacer] with `TelecomManager.placeCall` (developer.android.com: the default phone app should always place
 * calls this way, so the platform can tell the request came from it, and emergency calls work).
 */
class DeviceCallPlacer
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val sims: DeviceSimAccounts,
        private val emergency: EmergencyNumbers,
    ) : CallPlacer {
        private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)

        override fun canPlaceCalls(): Boolean =
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        override fun isEmergencyNumber(number: String): Boolean = emergency.isEmergency(number)

        override fun place(
            number: String,
            simId: String?,
        ): AppResult<Unit> = placeAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), simId)

        override fun placeEmergency(number: String): AppResult<Unit> {
            val telecom = telecom ?: return AppResult.Failure(AppError.Validation(NO_TELEPHONY))
            return placeWith(telecom, Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null), simId = null)
        }

        override fun placeVoicemail(simId: String?): AppResult<Unit> =
            placeAddress(Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null), simId)

        override fun sendSecretCode(digits: String): AppResult<Unit> {
            val telephony =
                context.getSystemService(TelephonyManager::class.java) ?: return AppResult.Failure(AppError.Validation(NO_TELEPHONY))
            return try {
                telephony.sendDialerSpecialCode(digits)
                AppResult.Success(Unit)
            } catch (e: SecurityException) {
                // Only the default phone app may send them.
                Timber.w(e, "Secret code refused")
                AppResult.Failure(AppError.PermissionDenied)
            } catch (e: UnsupportedOperationException) {
                Timber.w(e, "Secret codes unsupported")
                AppResult.Failure(AppError.Validation(NO_TELEPHONY))
            }
        }

        private fun placeAddress(
            address: Uri,
            simId: String?,
        ): AppResult<Unit> {
            val telecom = telecom
            return when {
                telecom == null -> AppResult.Failure(AppError.Validation(NO_TELEPHONY))
                !canPlaceCalls() -> AppResult.Failure(AppError.Validation(NO_PERMISSION))
                else -> placeWith(telecom, address, simId)
            }
        }

        // placeAddress() checks CALL_PHONE just before (placeEmergency needs none as the default phone app); a refusal
        // throws SecurityException, caught here.
        @SuppressLint("MissingPermission")
        private fun placeWith(
            telecom: TelecomManager,
            address: Uri,
            simId: String?,
        ): AppResult<Unit> =
            try {
                val extras = Bundle()
                simId?.let(sims::handleOf)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
                telecom.placeCall(address, extras)
                AppResult.Success(Unit)
            } catch (e: SecurityException) {
                Timber.w(e, "Placing a call was refused")
                AppResult.Failure(AppError.Validation(NO_PERMISSION))
            }

        override fun handOff(number: String): AppResult<Unit> {
            val dialer = telecom?.systemDialerPackage ?: return AppResult.Failure(AppError.Validation(NO_TELEPHONY))
            val dial =
                Intent(Intent.ACTION_DIAL, Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null))
                    .setPackage(dialer)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(dial)
                AppResult.Success(Unit)
            } catch (e: ActivityNotFoundException) {
                Timber.w(e, "No system phone app to hand the number to")
                AppResult.Failure(AppError.Validation(NO_TELEPHONY))
            }
        }

        /** Reasons in [AppError.Validation]. */
        companion object {
            /** The device can't place calls. */
            const val NO_TELEPHONY = "no_telephony"

            /** The call permission is missing. */
            const val NO_PERMISSION = "no_call_permission"
        }
    }
