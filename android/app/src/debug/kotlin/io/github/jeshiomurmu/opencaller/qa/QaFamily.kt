package io.github.jeshiomurmu.opencaller.qa

import android.content.Intent
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyCountsReporter
import io.github.jeshiomurmu.opencaller.core.data.family.FamilyGroups
import io.github.jeshiomurmu.opencaller.core.data.family.OtpWarningReport
import io.github.jeshiomurmu.opencaller.core.data.voip.NotificationRouter
import io.github.jeshiomurmu.opencaller.core.model.FamilyAlertKind
import io.github.jeshiomurmu.opencaller.core.model.FamilySharing
import java.time.ZoneId
import javax.inject.Inject

/**
 * Debug builds only: the steps of the emulator family test (`scripts/e2e/family.sh`, TC-FAM-001/002) for
 * [QaCommandReceiver]. They call the same repositories as the screens.
 *
 * - `family-create --es name N --es label L`: creates a group with this user as its admin.
 * - `family-invite`: "ok code=XXXXXXXX".
 * - `family-preview --es code C`: "ok group=N admin=L members=n" (what the consent screen shows).
 * - `family-join --es code C --es label L --ez consent B --ez alerts B --ez counts B`: joins; without the consent tick
 *   the repository refuses before any request.
 * - `family-status`: "ok none", or "ok role=OWNER|MEMBER alerts=B counts=B members=n received=n".
 * - `family-warn --es kind otp_during_call|otp_burst`: what the OTP guard reports after warning the user (the alert
 *   leaves the phone only when this member shares alerts).
 * - `family-counts`: sends yesterday's counts now (the daily worker's step).
 * - `catch-up`: fetches pending push items (a phone that missed the push), which shows a family alert to the admin.
 * - `family-leave`.
 */
class QaFamily
    @Inject
    constructor(
        private val family: FamilyGroups,
        private val warnings: OtpWarningReport,
        private val counts: FamilyCountsReporter,
        private val router: NotificationRouter,
    ) {
        /** Runs [cmd], or returns `null` when it isn't one of these commands. */
        suspend fun run(
            cmd: String?,
            intent: Intent,
        ): String? =
            when (cmd) {
                "family-create" -> {
                    describe(family.create(intent.getStringExtra("name").orEmpty(), intent.getStringExtra("label").orEmpty())) { "ok" }
                }

                "family-invite" -> {
                    describe(family.invite()) { "ok code=${it.code}" }
                }

                "family-preview" -> {
                    preview(intent.getStringExtra("code").orEmpty())
                }

                "family-join" -> {
                    join(intent)
                }

                "family-status" -> {
                    status()
                }

                "family-warn" -> {
                    warn(intent.getStringExtra("kind"))
                }

                "family-counts" -> {
                    "ok ${counts.sendYesterday(ZoneId.systemDefault())}"
                }

                "catch-up" -> {
                    router.catchUp().let { "ok" }
                }

                "family-leave" -> {
                    describe(family.leave()) { "ok" }
                }

                else -> {
                    null
                }
            }

        private suspend fun preview(code: String): String =
            describe(family.preview(code)) { "ok group=${it.groupName} admin=${it.adminLabel} members=${it.members}" }

        private suspend fun join(intent: Intent): String {
            val preview = (family.preview(intent.getStringExtra("code").orEmpty()) as? AppResult.Success)?.value ?: return "error preview"
            val sharing =
                FamilySharing(otpAlerts = intent.getBooleanExtra("alerts", false), dailyCounts = intent.getBooleanExtra("counts", false))
            val consent = intent.getBooleanExtra("consent", false)
            return describe(family.join(preview, intent.getStringExtra("label").orEmpty(), sharing, consent)) { "ok role=${it.myRole}" }
        }

        private suspend fun status(): String =
            describe(family.refresh()) { group ->
                if (group == null) {
                    "ok none"
                } else {
                    "ok role=${group.myRole} alerts=${group.mySharing.otpAlerts} counts=${group.mySharing.dailyCounts} " +
                        "members=${group.memberCount} received=${group.alerts.size}"
                }
            }

        private suspend fun warn(kind: String?): String {
            val alertKind = FamilyAlertKind.parse(kind) ?: return "error kind"
            warnings.warned(alertKind)
            return "ok"
        }

        private inline fun <T> describe(
            result: AppResult<T>,
            ok: (T) -> String,
        ): String =
            when (result) {
                is AppResult.Success -> ok(result.value)
                is AppResult.Failure -> "error ${result.error}"
            }
    }
