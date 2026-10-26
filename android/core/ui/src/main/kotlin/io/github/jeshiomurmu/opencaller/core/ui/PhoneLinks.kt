package io.github.jeshiomurmu.opencaller.core.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber

/** Links to other apps for a number: the SMS app. */
object PhoneLinks {
    /**
     * Opens the default SMS app to write to [number] (`ACTION_SENDTO`, `smsto:`), with [body] filled in when given
     * (a quick reply). Returns false when no app can send SMS.
     */
    fun message(
        context: Context,
        number: String,
        body: String? = null,
    ): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        body?.let { intent.putExtra("sms_body", it) }
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "No app to send a text message")
            false
        }
    }
}
