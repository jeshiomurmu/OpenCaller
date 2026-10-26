package io.github.jeshiomurmu.opencaller.core.telephony

import kotlinx.coroutines.flow.Flow

/**
 * The user's own quick replies (Me → Settings → Quick replies), for declining a call with a message and for the
 * after-call card's Message. An empty list means Android's built-in replies.
 */
fun interface QuickReplyTemplates {
    /** The replies in display order, emitting again when the user edits them. */
    fun replies(): Flow<List<String>>
}
