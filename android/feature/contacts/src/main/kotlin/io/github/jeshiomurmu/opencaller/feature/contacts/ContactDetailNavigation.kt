package io.github.jeshiomurmu.opencaller.feature.contacts

/** Where the contact detail screen leads. */
class ContactDetailNavigation(
    /** Edit the contact with this id. */
    val onEdit: (Long) -> Unit,
    /** Open the dial pad with this number (it asks for the call permission first). */
    val onDialPad: (String) -> Unit,
    /** The contact is gone (deleted elsewhere): leave the screen. */
    val onClose: () -> Unit,
)
