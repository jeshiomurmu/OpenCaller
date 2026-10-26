package io.github.jeshiomurmu.opencaller.feature.dialer.search

import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.model.CallLogEntry
import io.github.jeshiomurmu.opencaller.core.model.ContactSummary
import io.github.jeshiomurmu.opencaller.core.telephony.providers.CallLogSource
import io.github.jeshiomurmu.opencaller.core.telephony.providers.ContactsSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/**
 * What the dial pad can suggest: recent numbers from the call log first (as the default phone app), then the contacts'
 * numbers. Read on the device only; without a permission that source is simply left out.
 */
class DialSuggestions
    @Inject
    constructor(
        private val contacts: ContactsSource,
        private val callLog: CallLogSource,
    ) {
        private val matcher = T9Matcher()

        /** The candidates, again whenever the contacts or the call log change. */
        fun candidates(): Flow<List<T9Candidate>> {
            val recent = if (callLog.canRead()) callLog.observe().map { it.getOrNull().orEmpty() } else flowOf(emptyList())
            val people = if (contacts.canRead()) contacts.observeAll().map { it.getOrNull().orEmpty() } else flowOf(emptyList())
            return combine(recent, people, ::candidatesOf)
        }

        /** The best [limit] suggestions for [typed]; none for codes (`*`, `#`) or an empty entry. */
        fun search(
            typed: String,
            candidates: List<T9Candidate>,
            limit: Int = LIMIT,
        ): List<T9Match> =
            if (typed.isEmpty() ||
                typed.any { it == '*' || it == '#' }
            ) {
                emptyList()
            } else {
                matcher.search(typed, candidates, limit)
            }

        private fun candidatesOf(
            calls: List<CallLogEntry>,
            people: List<ContactSummary>,
        ): List<T9Candidate> {
            val named = people.flatMap { contact -> contact.phones.map { T9Candidate(contact.name, it.number?.e164 ?: it.dialString) } }
            val names = named.associate { it.dialString to it.name }
            val logged =
                calls
                    .mapNotNull { call -> call.callBackString?.let { T9Candidate(names[it] ?: call.cachedName, it) } }
                    .distinctBy { it.dialString }
            return logged + named
        }

        private companion object {
            const val LIMIT = 8
        }
    }
