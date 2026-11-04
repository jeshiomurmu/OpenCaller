package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.backup.MessageLocator
import io.github.jeshiomurmu.opencaller.core.data.backup.MessageRef
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * [MessageLocator] over the documented `Telephony.Sms` table: a message is found again by its date and address
 * (the provider's ids differ on another phone). Reading SMS needs the default SMS app role; without it, nothing is
 * found and backups carry no message categories. Message bodies are never read.
 */
class DeviceMessageLocator
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dispatchers: DispatcherProvider,
    ) : MessageLocator {
        override suspend fun describe(providerIds: List<Long>): List<MessageRef> =
            withContext(dispatchers.io) {
                providerIds.chunked(CHUNK).flatMap { ids ->
                    query("${Telephony.Sms._ID} IN (${ids.joinToString(",") { "?" }})", ids.map(Long::toString)) { cursor ->
                        cursor.toRef()
                    }.map { it.first }
                }
            }

        override suspend fun locate(refs: List<MessageRef>): Map<Long, Pair<Long, Long>> =
            withContext(dispatchers.io) {
                val found = mutableMapOf<Long, Pair<Long, Long>>()
                refs.chunked(CHUNK).forEach { batch ->
                    val rows =
                        query("${Telephony.Sms.DATE} IN (${batch.joinToString(",") { "?" }})", batch.map { it.date.toString() }) { cursor ->
                            cursor.toRef()
                        }
                    batch.forEach { ref ->
                        rows
                            .firstOrNull { (row, _) -> row.date == ref.date && sameAddress(row.address, ref.address) }
                            ?.let { (row, thread) -> found[ref.providerId] = row.providerId to thread }
                    }
                }
                found
            }

        // Each row with its thread id; nothing when the provider can't be read (not the default SMS app).
        private fun query(
            where: String,
            args: List<String>,
            read: (Cursor) -> MessageRef,
        ): List<Pair<MessageRef, Long>> =
            try {
                context.contentResolver
                    .query(Telephony.Sms.CONTENT_URI, PROJECTION, where, args.toTypedArray(), null)
                    ?.use { cursor ->
                        buildList { while (cursor.moveToNext()) add(read(cursor) to cursor.getLong(COL_THREAD)) }
                    }.orEmpty()
            } catch (e: SecurityException) {
                Timber.i(e, "SMS not readable (not the default SMS app)")
                emptyList()
            }

        private companion object {
            const val CHUNK = 500
            const val COL_ID = 0
            const val COL_THREAD = 1
            const val COL_DATE = 2
            const val COL_ADDRESS = 3

            fun Cursor.toRef() = MessageRef(getLong(COL_ID), getLong(COL_DATE), getString(COL_ADDRESS).orEmpty())

            val PROJECTION = arrayOf(Telephony.Sms._ID, Telephony.Sms.THREAD_ID, Telephony.Sms.DATE, Telephony.Sms.ADDRESS)

            // Phones store an address as typed or as received ("+880 1712…", "01712…"): compare the last digits.
            fun sameAddress(
                a: String,
                b: String,
            ): Boolean {
                val left = a.filter(Char::isDigit)
                val right = b.filter(Char::isDigit)
                return if (left.length >= COMPARED_DIGITS && right.length >= COMPARED_DIGITS) {
                    left.takeLast(COMPARED_DIGITS) == right.takeLast(COMPARED_DIGITS)
                } else {
                    a.trim().equals(b.trim(), ignoreCase = true)
                }
            }

            const val COMPARED_DIGITS = 9
        }
    }
