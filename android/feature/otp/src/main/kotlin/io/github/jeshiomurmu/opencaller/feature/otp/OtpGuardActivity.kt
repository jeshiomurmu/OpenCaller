package io.github.jeshiomurmu.opencaller.feature.otp

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.designsystem.theme.OpenCallerTheme
import javax.inject.Inject

/** The OTP guard's full-screen warning (TC-OTP-005), over the lock screen and other apps. */
@AndroidEntryPoint
class OtpGuardActivity : ComponentActivity() {
    @Inject
    lateinit var alarm: OtpGuardAlarm

    private val viewModel: OtpGuardViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Scammers ask victims to install screen-sharing or "support" apps; their overlays must not cover or fake the
        // warning (docs/security.md, MASVS-PLATFORM-3). System windows (the call, the keyguard) still show.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) window.setHideOverlayWindows(true)
        alarm.cancelCallWarning()
        setContent { OpenCallerTheme { OtpGuardRoute(viewModel = viewModel, onClose = ::finish) } }
    }

    /** Extras and the launch intent. */
    companion object {
        /** Who sent the code (a name), if known. */
        const val EXTRA_FROM = "io.github.jeshiomurmu.opencaller.extra.OTP_FROM"

        /** True when the code came as a flash call. */
        const val EXTRA_FLASH_CALL = "io.github.jeshiomurmu.opencaller.extra.OTP_FLASH_CALL"

        /** Opens the warning for a code from [codeFrom]. */
        fun intent(
            context: Context,
            codeFrom: String?,
            flashCall: Boolean,
        ): Intent =
            Intent(context, OtpGuardActivity::class.java)
                .putExtra(EXTRA_FROM, codeFrom)
                .putExtra(EXTRA_FLASH_CALL, flashCall)
    }
}
