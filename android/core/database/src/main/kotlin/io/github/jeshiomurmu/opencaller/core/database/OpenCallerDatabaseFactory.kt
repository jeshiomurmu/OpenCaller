package io.github.jeshiomurmu.opencaller.core.database

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Builds [OpenCallerDatabase] instances encrypted with SQLCipher (Room 2 `SupportOpenHelperFactory` integration). */
object OpenCallerDatabaseFactory {
    /** File name of the production database. */
    const val DATABASE_NAME = "opencaller.db"

    /**
     * Opens (creating if needed) the database [name] encrypted with [passphrase], or an in-memory database
     * when [name] is `null`. The passphrase array is kept by SQLCipher for reconnects; don't clear it.
     */
    fun create(
        context: Context,
        passphrase: ByteArray,
        name: String? = DATABASE_NAME,
    ): OpenCallerDatabase {
        // The SQLCipher native library must be loaded before first use; repeated calls are no-ops.
        System.loadLibrary("sqlcipher")
        val builder =
            if (name == null) {
                Room.inMemoryDatabaseBuilder(context, OpenCallerDatabase::class.java)
            } else {
                Room.databaseBuilder(context, OpenCallerDatabase::class.java, name)
            }
        return builder.openHelperFactory(SupportOpenHelperFactory(passphrase)).build()
    }
}
