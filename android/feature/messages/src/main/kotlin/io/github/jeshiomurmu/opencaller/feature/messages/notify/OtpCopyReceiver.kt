package io.github.jeshiomurmu.opencaller.feature.messages.notify

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle

/** The OTP notification's Copy: puts the code on the clipboard, marked sensitive so Android doesn't preview it. */
class OtpCopyReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { intent.action == ACTION_COPY && it.isNotBlank() } ?: return
        val clip =
            ClipData.newPlainText(
                context.getString(io.github.jeshiomurmu.opencaller.feature.messages.R.string.otp_notification_title),
                code,
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }

    /** Action and extra. */
    companion object {
        /** Copy the code. */
        const val ACTION_COPY = "io.github.jeshiomurmu.opencaller.action.COPY_CODE"

        /** The code. */
        const val EXTRA_CODE = "code"
    }
}
