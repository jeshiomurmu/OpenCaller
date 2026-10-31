package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.content.ContentUris
import android.net.Uri
import android.os.Build
import android.provider.Telephony

/**
 * Addresses of an MMS's parts and addresses in Android's message store. Android 11 added
 * `Telephony.Mms.Part.getPartUriForMessage` and `Telephony.Mms.Addr.getAddrUriForMessage`; on Android 10 the same
 * `content://mms/{id}/part` and `content://mms/{id}/addr` addresses are built from `Telephony.Mms.CONTENT_URI`.
 */
internal object MmsUris {
    /** The parts of message [messageId]. */
    fun parts(messageId: Long): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Telephony.Mms.Part.getPartUriForMessage(messageId.toString())
        } else {
            Telephony.Mms.CONTENT_URI
                .buildUpon()
                .appendPath(messageId.toString())
                .appendPath(PART)
                .build()
        }

    /** The addresses of message [messageId]. */
    fun addresses(messageId: Long): Uri =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Telephony.Mms.Addr.getAddrUriForMessage(messageId.toString())
        } else {
            Telephony.Mms.CONTENT_URI
                .buildUpon()
                .appendPath(messageId.toString())
                .appendPath(ADDR)
                .build()
        }

    /** One part, by its own row id (`Telephony.Mms.Part.CONTENT_URI`, Android 10). */
    fun part(partId: Long): Uri = ContentUris.withAppendedId(Telephony.Mms.Part.CONTENT_URI, partId)

    private const val PART = "part"
    private const val ADDR = "addr"
}
