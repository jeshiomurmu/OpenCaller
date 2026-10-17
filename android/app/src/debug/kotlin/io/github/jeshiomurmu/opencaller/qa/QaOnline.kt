package io.github.jeshiomurmu.opencaller.qa

import android.content.Intent
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.ReportRepository
import io.github.jeshiomurmu.opencaller.core.data.SpamListRepository
import io.github.jeshiomurmu.opencaller.core.data.account.Account
import io.github.jeshiomurmu.opencaller.core.data.reports.ReportUploader
import io.github.jeshiomurmu.opencaller.core.data.sync.SpamListSyncRepository
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ReportLabel
import javax.inject.Inject

/**
 * Debug builds only: the online steps of TC-E2E-001 (`scripts/e2e/report_to_block.sh`) for [QaCommandReceiver]. They
 * call the same repositories as the screens and workers, synchronously, so the script can check each step.
 *
 * - `signin-start --es number E164` / `signin-verify --es number E164 --es code C`: sign-in (the code comes from the
 *   development server's log).
 * - `report --es number E164 [--es label SPAM|NOT_SPAM] [--es category C]`: queues a report like the report sheet.
 * - `upload`: sends queued reports now ("ok Done(sent=1)").
 * - `sync`: updates the spam list now ("ok Updated(…)").
 * - `listed --es number E164`: "ok score=0.43" when the spam list on the phone has the number, else "ok none".
 */
class QaOnline
    @Inject
    constructor(
        private val account: Account,
        private val reports: ReportRepository,
        private val uploader: ReportUploader,
        private val sync: SpamListSyncRepository,
        private val spam: SpamListRepository,
    ) {
        /** Runs [cmd], or returns `null` when it isn't one of these commands. */
        suspend fun run(
            cmd: String?,
            intent: Intent,
        ): String? =
            when (cmd) {
                "signin-start" -> number(intent) { describe(account.sendCode(it)) { seconds -> "ok expires=$seconds" } }
                "signin-verify" -> number(intent) { describe(account.verify(it, intent.getStringExtra("code").orEmpty())) { "ok" } }
                "report" -> number(intent) { report(it, intent) }
                "upload" -> "ok ${uploader.upload()}"
                "sync" -> describe(sync.sync()) { "ok $it" }
                "listed" -> number(intent) { listed(it) }
                else -> null
            }

        private suspend fun report(
            number: PhoneNumber,
            intent: Intent,
        ): String {
            val label = if (intent.getStringExtra("label").equals("NOT_SPAM", ignoreCase = true)) ReportLabel.NOT_SPAM else ReportLabel.SPAM
            return describe(reports.queue(number, label, intent.getStringExtra("category"))) { "ok id=$it" }
        }

        private suspend fun listed(number: PhoneNumber): String =
            describe(spam.find(number)) { hit -> if (hit == null) "ok none" else "ok score=${hit.score}" }

        private inline fun number(
            intent: Intent,
            block: (PhoneNumber) -> String,
        ): String = intent.getStringExtra("number")?.takeIf(PhoneNumber::isE164)?.let { block(PhoneNumber(it)) } ?: "error number"

        private inline fun <T> describe(
            result: AppResult<T>,
            ok: (T) -> String,
        ): String =
            when (result) {
                is AppResult.Success -> ok(result.value)
                is AppResult.Failure -> "error ${result.error}"
            }
    }
