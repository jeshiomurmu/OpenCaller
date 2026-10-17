package io.github.jeshiomurmu.opencaller.qa

import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.data.BlockRuleRepository
import io.github.jeshiomurmu.opencaller.core.data.ScreeningEventRepository
import io.github.jeshiomurmu.opencaller.core.data.SpamListHit
import io.github.jeshiomurmu.opencaller.core.data.SpamListRepository
import io.github.jeshiomurmu.opencaller.core.datastore.UserSettingsDataSource
import io.github.jeshiomurmu.opencaller.core.model.OnboardingStep
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.SpamThreshold
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleValidation
import io.github.jeshiomurmu.opencaller.core.telephony.screening.RuleValidator
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningWarmUp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Debug builds only: commands for `scripts/qa/blocking.sh`, sent with
 * `adb shell am broadcast -n <package>/io.github.jeshiomurmu.opencaller.qa.QaCommandReceiver --es cmd <command> …`.
 * The result ("ok …" or "error …") comes back as the broadcast's result data.
 *
 * - `reset`: no rules, no screening log, empty spam list, default blocking settings, onboarding done, no overrides.
 * - `rule --es type EXACT|PREFIX|COUNTRY|REGEX --es pattern P --es action BLOCK|SILENCE|ALLOW`: validated like the
 *   rules editor, then saved.
 * - `settings [--ez hidden B] [--ez foreign B] [--ez nonContacts B] [--ez topSpammers B] [--es threshold T]
 *   [--es allowed US,GB]`
 * - `spam --es number E164 --es score 0.9 [--es category C]`: adds a spam list entry.
 * - `region [--es region BD]`: overrides the SIM country (no value: back to the real one).
 * - `verified --es number E164`: treats a number as a verified business.
 * - `role`: "ok held=true|false" for the call screening role (`cmd role get-role-holders` doesn't exist on API 29).
 * - Sign-in, reports and the spam-list update for TC-E2E-001: see [QaOnline].
 */
@AndroidEntryPoint
class QaCommandReceiver : BroadcastReceiver() {
    @Inject lateinit var rules: BlockRuleRepository

    @Inject lateinit var events: ScreeningEventRepository

    @Inject lateinit var spam: SpamListRepository

    @Inject lateinit var settings: UserSettingsDataSource

    @Inject lateinit var hooks: QaHooks

    @Inject lateinit var export: QaExport

    @Inject lateinit var validator: RuleValidator

    @Inject lateinit var warmUp: ScreeningWarmUp

    @Inject lateinit var dispatchers: DispatcherProvider

    @Inject lateinit var online: QaOnline

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val roles = context.getSystemService(RoleManager::class.java)
        CoroutineScope(dispatchers.io).launch {
            val result =
                if (intent.getStringExtra(EXTRA_CMD) ==
                    "role"
                ) {
                    "ok held=${roles.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)}"
                } else {
                    run(intent)
                }
            pending.resultData = result
            pending.finish()
        }
    }

    private suspend fun run(intent: Intent): String =
        when (val cmd = intent.getStringExtra(EXTRA_CMD)) {
            "reset" -> {
                reset()
            }

            "rule" -> {
                addRule(intent)
            }

            "settings" -> {
                updateSettings(intent)
            }

            "spam" -> {
                addSpam(intent)
            }

            "region" -> {
                setRegion(intent.getStringExtra("region")?.uppercase())
            }

            "verified" -> {
                number(intent)?.let { hooks.addVerifiedBusiness(it).let { "ok" } } ?: "error number"
            }

            else -> {
                online.run(cmd, intent) ?: "error unknown command $cmd"
            }
        }

    // Like a SIM change on a phone: the new country's numbering metadata is loaded before the next call.
    private suspend fun setRegion(region: String?): String {
        hooks.setRegion(region)
        warmUp.warmUp()
        return "ok region=$region"
    }

    private suspend fun reset(): String {
        val cleared = listOf(rules.deleteAll(), events.deleteAll(), spam.deleteAll())
        hooks.clear()
        export.clear()
        settings.update { UserPreferences(onboardingStep = OnboardingStep.DONE) }
        return if (cleared.all { it is AppResult.Success }) "ok" else "error storage"
    }

    private suspend fun addRule(intent: Intent): String {
        val type = enumOrNull<RuleType>(intent.getStringExtra("type")) ?: return "error type"
        val action = enumOrNull<RuleAction>(intent.getStringExtra("action")) ?: RuleAction.BLOCK
        return when (val validation = validator.validate(type, intent.getStringExtra("pattern").orEmpty(), hooks.homeRegionOverride())) {
            is RuleValidation.Invalid -> "error ${validation.problem}"
            is RuleValidation.Valid -> rules.add(type, validation.pattern, action).describe { "ok id=$it pattern=${validation.pattern}" }
        }
    }

    private suspend fun updateSettings(intent: Intent): String {
        settings.update { prefs ->
            prefs.copy(
                blockHiddenNumbers = intent.getBooleanExtra("hidden", prefs.blockHiddenNumbers),
                blockForeignNumbers = intent.getBooleanExtra("foreign", prefs.blockForeignNumbers),
                blockNonContacts = intent.getBooleanExtra("nonContacts", prefs.blockNonContacts),
                blockTopSpammers = intent.getBooleanExtra("topSpammers", prefs.blockTopSpammers),
                spamThreshold = enumOrNull<SpamThreshold>(intent.getStringExtra("threshold")) ?: prefs.spamThreshold,
                allowedRegionCodes =
                    intent
                        .getStringExtra("allowed")
                        ?.split(',')
                        ?.map { it.trim().uppercase() }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        ?: prefs.allowedRegionCodes,
            )
        }
        return "ok"
    }

    private suspend fun addSpam(intent: Intent): String {
        val number = number(intent)
        val score = intent.getStringExtra("score")?.toFloatOrNull()?.takeIf { it in 0f..1f }
        return if (number == null || score == null) {
            "error number or score"
        } else {
            val hit = SpamListHit(number, score, intent.getStringExtra("category").orEmpty(), reportCount = 1)
            spam.upsert(listOf(hit), QA_LIST_VERSION).describe { "ok" }
        }
    }

    private fun <T> AppResult<T>.describe(ok: (T) -> String): String =
        when (this) {
            is AppResult.Success -> ok(value)
            is AppResult.Failure -> "error $error"
        }

    private fun number(intent: Intent): PhoneNumber? = intent.getStringExtra("number")?.takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)

    private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? = enumValues<T>().firstOrNull { it.name == name?.uppercase() }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val QA_LIST_VERSION = 1_000L
    }
}
