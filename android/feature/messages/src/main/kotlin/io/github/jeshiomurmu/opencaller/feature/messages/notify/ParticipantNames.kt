package io.github.jeshiomurmu.opencaller.feature.messages.notify

import io.github.jeshiomurmu.opencaller.core.model.Participant
import io.github.jeshiomurmu.opencaller.core.telephony.NumberDisplay
import io.github.jeshiomurmu.opencaller.core.telephony.screening.ContactLookup
import javax.inject.Inject
import javax.inject.Singleton

/** How the people of a conversation are shown: their contact name, else their number formatted, else the sender ID. */
class ParticipantNames
    @Inject
    constructor(
        private val contacts: ContactLookup,
        private val numbers: NumberDisplay,
    ) {
        /** The contact name of [participant], if any. */
        suspend fun contactName(participant: Participant): String? =
            participant.number?.let { contacts.displayName(it) }?.takeIf(String::isNotBlank)

        /** [participant]'s address for display (no contact lookup). */
        fun address(participant: Participant): String = participant.number?.let { numbers.format(it) } ?: participant.address

        /** A participant for [address] (a number as typed or as stored, or a sender ID). */
        fun participant(address: String): Participant = Participant(address, numbers.parse(address))

        /** The name to show for [participant]. */
        suspend fun title(participant: Participant): String = contactName(participant) ?: address(participant)
    }

/** The conversation open on screen right now, whose messages need no notification. */
@Singleton
class ActiveThread
    @Inject
    constructor() {
        @Volatile
        private var threadId: Long? = null

        /** True when [threadId] is on screen. */
        fun isShowing(threadId: Long): Boolean = this.threadId == threadId

        /** The thread screen of [threadId] is in front (or `null`: none is). */
        fun set(threadId: Long?) {
            this.threadId = threadId
        }
    }
