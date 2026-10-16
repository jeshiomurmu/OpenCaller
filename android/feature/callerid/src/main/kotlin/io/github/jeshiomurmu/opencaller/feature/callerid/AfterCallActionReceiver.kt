package io.github.jeshiomurmu.opencaller.feature.callerid

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.common.flatMap
import io.github.jeshiomurmu.opencaller.core.common.map
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ReportRepository
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Block and Not spam on the after-call notification. Block adds an exact block rule; Not spam adds an exact allow
 * rule (so the number is never blocked here again) and queues a not-spam report, which stays on the phone until
 * reporting arrives in Phase 5. Not exported: only OpenCaller's own PendingIntents reach it.
 */
@AndroidEntryPoint
class AfterCallActionReceiver : BroadcastReceiver() {
    @Inject
    lateinit var rules: BlockRuleRepository

    @Inject
    lateinit var reports: ReportRepository

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
                val done = if (action == ACTION_BLOCK) block(number) else notSpam(number)
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

    private suspend fun block(number: PhoneNumber): AppResult<Unit> = rules.add(RuleType.EXACT, number.e164, RuleAction.BLOCK).map {}

    private suspend fun notSpam(number: PhoneNumber): AppResult<Unit> =
        rules
            .deleteExactRules(number, setOf(RuleAction.BLOCK, RuleAction.SILENCE))
            .flatMap { rules.add(RuleType.EXACT, number.e164, RuleAction.ALLOW) }
            .flatMap { reports.queue(number, ReportLabel.NOT_SPAM) }
            .map {}

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
