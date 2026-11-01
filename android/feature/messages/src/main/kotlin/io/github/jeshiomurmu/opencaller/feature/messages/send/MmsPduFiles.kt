package io.github.jeshiomurmu.opencaller.feature.messages.send

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Temporary files that hold MMS PDUs while Android's MMS service sends or downloads them, in the app's cache and
 * served to the service by [MmsPduProvider]. Each file is deleted once its result arrives.
 */
@Singleton
class MmsPduFiles
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val directory: File get() = File(context.cacheDir, DIRECTORY).apply { mkdirs() }

        /** A new empty file's name. */
        @Throws(IOException::class)
        fun create(): String {
            val name = UUID.randomUUID().toString()
            if (!fileOf(name).createNewFile()) throw IOException("PDU file exists")
            return name
        }

        /** A new file holding [bytes]. */
        @Throws(IOException::class)
        fun write(bytes: ByteArray): String = create().also { fileOf(it).writeBytes(bytes) }

        /** The bytes of file [name]. */
        @Throws(IOException::class)
        fun read(name: String): ByteArray = fileOf(name).readBytes()

        /** Deletes file [name]. */
        fun delete(name: String) {
            fileOf(name).delete()
        }

        /** The address Android's MMS service reads or writes file [name] through. */
        fun uriOf(name: String): Uri =
            Uri
                .Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(name)
                .build()

        /** The file behind [name]; refuses anything but a name made by [create]. */
        fun fileOf(name: String): File {
            require(NAME.matches(name)) { "not a PDU file name" }
            return File(directory, name)
        }

        /** Where the files live and how they are named. */
        companion object {
            private const val DIRECTORY = "mms_pdu"
            private val NAME = Regex("^[0-9a-f-]{36}$")

            /** The authority of [MmsPduProvider] (declared in the manifest as `${applicationId}.messaging.pdu`). */
            fun authority(context: Context): String = "${context.packageName}.messaging.pdu"
        }
    }
