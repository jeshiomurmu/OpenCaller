package io.github.jeshiomurmu.opencaller.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** A SIM (Telecom phone account) the user can call with. [label] is the name Android gives it ("Work", "Airtel"). */
data class SimOption(
    val id: String,
    val label: String,
)

/**
 * The SIMs OpenCaller can offer for a call.
 *
 * @property defaultId the SIM Android uses for calls, `null` when the user hasn't chosen one ("Ask every time").
 */
data class SimChoice(
    val options: List<SimOption> = emptyList(),
    val defaultId: String? = null,
) {
    /** True when the dial pad must ask (TC-DIAL-003): more than one SIM and no default. */
    val needsChoice: Boolean get() = options.size > 1 && defaultId == null
}

/** The SIMs for outgoing calls. */
fun interface SimAccounts {
    /**
     * The SIMs and the default. Empty when they can't be read (the phone state or phone numbers permission is
     * missing): Telecom then asks itself and OpenCaller's in-call screen shows its SIM chooser.
     */
    fun current(): SimChoice
}

/**
 * [SimAccounts] from `TelecomManager.getCallCapablePhoneAccounts` and `getDefaultOutgoingPhoneAccount("tel")`
 * (READ_PHONE_STATE); SIM names come from `getPhoneAccount`, which needs READ_PHONE_NUMBERS on Android 12+. OpenCaller
 * never guesses a SIM's name (docs/phase.md issue 71), so without the names there is no choice to offer here.
 */
@Singleton
class DeviceSimAccounts
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : SimAccounts {
        private val handles = ConcurrentHashMap<String, PhoneAccountHandle>()

        override fun current(): SimChoice {
            val telecom = context.getSystemService(TelecomManager::class.java)?.takeIf { canRead() } ?: return SimChoice()
            return try {
                read(telecom)
            } catch (e: SecurityException) {
                Timber.w(e, "SIMs not readable")
                SimChoice()
            }
        }

        /** The phone account of [id], from the last [current]. */
        fun handleOf(id: String): PhoneAccountHandle? = handles[id]

        // canRead() checked both permissions just before; a revoked one is caught as SecurityException.
        @SuppressLint("MissingPermission")
        private fun read(telecom: TelecomManager): SimChoice {
            val accounts = telecom.callCapablePhoneAccounts
            val options =
                accounts.mapNotNull { handle ->
                    val label =
                        telecom
                            .getPhoneAccount(handle)
                            ?.label
                            ?.toString()
                            ?.takeIf(String::isNotBlank) ?: return SimChoice()
                    val id = idOf(handle)
                    handles[id] = handle
                    SimOption(id, label)
                }
            val default = telecom.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL)?.let(::idOf)
            return SimChoice(options, default)
        }

        private fun canRead(): Boolean =
            granted(Manifest.permission.READ_PHONE_STATE) &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(Manifest.permission.READ_PHONE_NUMBERS))

        private fun granted(permission: String): Boolean = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

        private fun idOf(handle: PhoneAccountHandle): String = "${handle.componentName.flattenToString()}/${handle.id}"
    }
