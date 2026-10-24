package io.github.jeshiomurmu.opencaller.feature.calllog

/** What the user can do with a row of the call log. */
internal class CallActions(
    val onCall: () -> Unit,
    val onMessage: () -> Unit,
    val onOpenContact: () -> Unit,
    val onAddContact: () -> Unit,
    val onBlock: () -> Unit,
    val onDelete: () -> Unit,
)
