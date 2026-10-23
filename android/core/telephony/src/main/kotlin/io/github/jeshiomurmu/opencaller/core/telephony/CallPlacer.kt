package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import timber.log.Timber
import javax.inject.Inject

/** Places calls from OpenCaller's dial pad. */
interface CallPlacer {
    /** True when OpenCaller may place calls itself (the call permission, asked only of the default phone app). */
    fun canPlaceCalls(): Boolean

    /** Places a call to [number] through Telecom (emergency numbers included, as the default phone app). */
    fun place(number: String): AppResult<Unit>

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
    ) : CallPlacer {
        private val telecom: TelecomManager? = context.getSystemService(TelecomManager::class.java)

        override fun canPlaceCalls(): Boolean =
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

        override fun place(number: String): AppResult<Unit> {
            val telecom = telecom
            return when {
                telecom == null -> AppResult.Failure(AppError.Validation(NO_TELEPHONY))
                !canPlaceCalls() -> AppResult.Failure(AppError.Validation(NO_PERMISSION))
                else -> placeWith(telecom, number)
            }
        }

        private fun placeWith(
            telecom: TelecomManager,
            number: String,
        ): AppResult<Unit> =
            try {
                telecom.placeCall(Uri.fromParts(TEL, number, null), Bundle())
                AppResult.Success(Unit)
            } catch (e: SecurityException) {
                Timber.w(e, "Placing a call was refused")
                AppResult.Failure(AppError.Validation(NO_PERMISSION))
            }

        override fun handOff(number: String): AppResult<Unit> {
            val dialer = telecom?.systemDialerPackage ?: return AppResult.Failure(AppError.Validation(NO_TELEPHONY))
            val dial =
                Intent(Intent.ACTION_DIAL, Uri.fromParts(TEL, number, null))
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

            private const val TEL = "tel"
        }
    }
