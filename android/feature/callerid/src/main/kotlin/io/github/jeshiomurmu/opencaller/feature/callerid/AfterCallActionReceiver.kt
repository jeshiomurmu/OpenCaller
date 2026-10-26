package io.github.jeshiomurmu.opencaller.feature.callerid

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Block and Not spam on the after-call notification ([AfterCallActions]). Not exported: only OpenCaller's own
 * PendingIntents reach it.
 */
@AndroidEntryPoint
class AfterCallActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var actions: AfterCallActions

    @Inject
    lateinit var normalizer: PhoneNumberNormalizer

    @Inject
    lateinit var dispatchers: DispatcherProvider

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val number = intent.getStringExtra(EXTRA_NUMBER)?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber) ?: return
        val action = intent.action?.takeIf { it == ACTION_BLOCK || it == ACTION_NOT_SPAM } ?: return
        context.getSystemService(NotificationManager::class.java).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        val pending = goAsync()
        CoroutineScope(dispatchers.io).launch {
            try {
                val done: AppResult<Unit> = if (action == ACTION_BLOCK) actions.block(number) else actions.notSpam(number)
                val message =
                    when {
                        done is AppResult.Failure -> context.getString(R.string.after_call_failed)
                        action == ACTION_BLOCK -> context.getString(R.string.after_call_blocked, normalizer.masked(number))
                        else -> context.getString(R.string.after_call_marked_not_spam, normalizer.masked(number))
                    }
                withContext(dispatchers.main) { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            } finally {
                pending.finish()
            }
        }
    }

    /** Actions and extras. */
    companion object {
        /** Add an exact block rule for the number. */
        const val ACTION_BLOCK = "io.github.jeshiomurmu.opencaller.action.AFTER_CALL_BLOCK"

        /** Mark the number as not spam. */
        const val ACTION_NOT_SPAM = "io.github.jeshiomurmu.opencaller.action.AFTER_CALL_NOT_SPAM"

        /** E.164 number. */
        const val EXTRA_NUMBER = "io.github.jeshiomurmu.opencaller.extra.NUMBER"

        /** Notification to remove. */
        const val EXTRA_NOTIFICATION_ID = "io.github.jeshiomurmu.opencaller.extra.NOTIFICATION_ID"
    }
}
