package io.github.jeshiomurmu.opencaller.feature.messages

import android.content.BroadcastReceiver
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the work of the messaging receivers and service off the main thread, in one app-wide scope: a broadcast's
 * work outlives `onReceive`, so it is kept alive with `goAsync()` until it ends.
 */
@Singleton
class MessagingWork
    @Inject
    constructor(
        dispatchers: DispatcherProvider,
    ) {
        private val scope =
            CoroutineScope(SupervisorJob() + dispatchers.io + CoroutineExceptionHandler { _, e -> Timber.e(e, "Messaging work failed") })

        /** Runs [block] for a receiver's [pending] broadcast and finishes it afterwards. */
        fun forBroadcast(
            pending: BroadcastReceiver.PendingResult,
            block: suspend () -> Unit,
        ) {
            scope.launch {
                try {
                    block()
                } finally {
                    pending.finish()
                }
            }
        }

        /** Runs [block] and then [done]. */
        fun run(
            done: () -> Unit = {},
            block: suspend () -> Unit,
        ) {
            scope.launch {
                try {
                    block()
                } finally {
                    done()
                }
            }
        }
    }
