package io.github.jeshiomurmu.opencaller.core.telephony.providers

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import timber.log.Timber

/**
 * Emits once at once and again whenever the content at [uri] (or below it) changes, coalescing bursts. Without the
 * permission to watch it, it still emits once, so the reader can report the missing permission.
 */
internal fun ContentResolver.changes(uri: Uri): Flow<Unit> =
    callbackFlow {
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        val watching =
            try {
                registerContentObserver(uri, true, observer)
                true
            } catch (e: SecurityException) {
                Timber.w(e, "Not allowed to watch a provider")
                false
            }
        trySend(Unit)
        awaitClose { if (watching) unregisterContentObserver(observer) }
    }.conflate()
