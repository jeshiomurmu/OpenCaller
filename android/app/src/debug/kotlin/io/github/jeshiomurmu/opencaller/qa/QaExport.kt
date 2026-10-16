package io.github.jeshiomurmu.opencaller.qa

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jeshiomurmu.opencaller.core.common.Clock
import io.github.jeshiomurmu.opencaller.core.model.CallerIdSurface
import io.github.jeshiomurmu.opencaller.core.model.RiskLevel
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.telephony.screening.CallerIdListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningEventListener
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ScreeningResult
import io.github.jeshiomurmu.opencaller.core.ui.CallerCardState
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug builds only: appends every screening decision and every caller ID shown to tab-separated files in the app's
 * private storage, read by `scripts/qa/blocking.sh` with `adb shell run-as <package> cat files/qa/<file>`.
 *
 * `screening_events.tsv`: id, time, direction, decision, reason, number, latency ms, spam score, risk level.
 * `caller_id.tsv`: time, surface, card state, number.
 */
@Singleton
class QaExport
    @Inject
    constructor(
        @ApplicationContext context: Context,
        private val clock: Clock,
    ) : ScreeningEventListener,
        CallerIdListener {
        private val dir = File(context.filesDir, DIR)
        private val writer = Executors.newSingleThreadExecutor()

        override fun onScreened(
            event: ScreeningEvent,
            result: ScreeningResult,
        ) {
            val risk = result.caller.spamVerdict?.level ?: RiskLevel.NONE
            append(
                EVENTS,
                listOf(
                    event.id,
                    event.atMillis,
                    event.direction,
                    event.decision,
                    event.reason.code,
                    event.number?.e164 ?: "-",
                    event.latencyMs,
                    event.spamScore ?: "-",
                    risk,
                ),
            )
        }

        override fun onCallerIdShown(
            result: ScreeningResult,
            surface: CallerIdSurface,
        ) {
            append(CALLER_ID, listOf(clock.nowMillis(), surface, CallerCardState.of(result.caller), result.caller.number?.e164 ?: "-"))
        }

        /** Deletes both files. */
        fun clear() {
            writer.execute { dir.listFiles()?.forEach(File::delete) }
        }

        private fun append(
            name: String,
            fields: List<Any>,
        ) {
            writer.execute {
                try {
                    dir.mkdirs()
                    File(dir, name).appendText(fields.joinToString("\t", postfix = "\n"))
                } catch (e: IOException) {
                    Timber.w(e, "QA export failed")
                }
            }
        }

        private companion object {
            const val DIR = "qa"
            const val EVENTS = "screening_events.tsv"
            const val CALLER_ID = "caller_id.tsv"
        }
    }
