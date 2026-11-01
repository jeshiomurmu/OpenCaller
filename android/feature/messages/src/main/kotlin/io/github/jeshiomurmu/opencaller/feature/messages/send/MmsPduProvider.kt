package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException

/**
 * Serves [MmsPduFiles] to Android's MMS service. Not exported: the service only gets the one file of a send or a
 * download, through the URI grant Android makes for `SmsManager.sendMultimediaMessage` and
 * `downloadMultimediaMessage` (`grantUriPermissions` in the manifest). Nothing else is readable through it.
 */
class MmsPduProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor {
        val context = context ?: throw FileNotFoundException("no context")
        val name = uri.lastPathSegment ?: throw FileNotFoundException("no file")
        val file =
            try {
                MmsPduFiles(context).fileOf(name)
            } catch (e: IllegalArgumentException) {
                throw FileNotFoundException("not a PDU file").also { it.initCause(e) }
            }
        if (uri.pathSegments.size != 1 || !file.exists()) throw FileNotFoundException("no such PDU file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun getType(uri: Uri): String = PDU_TYPE

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val PDU_TYPE = "application/vnd.wap.mms-message"
    }
}
