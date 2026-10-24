package io.github.jeshiomurmu.opencaller.feature.calllog

import io.github.jeshiomurmu.opencaller.core.common.AppResult
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.ScreeningEvent
import io.github.jeshiomurmu.opencaller.core.telephony.providers.CallLogSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/** What the call log is built from, all read on the device: Android's call log, the contacts and the screening log. */
class CallLogInputs
    @Inject
    constructor(
        /** Android's call log. */
        val calls: CallLogSource,
        /** The contacts, for names and Open contact. */
        val contacts: ContactsSource,
        /** OpenCaller's screening decisions, for the blocked and spam marks. */
        val screening: ScreeningHistory,
    )

/** OpenCaller's screening log, joined with Android's call log for the blocked and spam filters. */
fun interface ScreeningHistory {
    /** The most recent screening decisions, newest first, again after every new one. */
    fun recent(): Flow<List<ScreeningEvent>>
}

/** Block from the call log, with Undo (docs/design.md §7: blocking is reversible). */
interface NumberBlocker {
    /** Adds an exact block rule for [number]; returns the rule's id for [undo]. */
    suspend fun block(number: PhoneNumber): AppResult<Long>

    /** Removes the rule [ruleId] added by [block]. */
    suspend fun undo(ruleId: Long): AppResult<Unit>
}
