package io.github.jeshiomurmu.opencaller.feature.calllog

/** Where the call log leads. Other features' screens are reached through the app's navigation. */
class CallLogNavigation(
    /** Open the dial pad, with a number to call when given. */
    val onDialPad: (String?) -> Unit,
    /** Open the contact with this id. */
    val onOpenContact: (Long) -> Unit,
    /** Create a contact with this number. */
    val onAddContact: (String) -> Unit,
)
