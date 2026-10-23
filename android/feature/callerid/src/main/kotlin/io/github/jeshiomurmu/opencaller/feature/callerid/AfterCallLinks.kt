package io.github.jeshiomurmu.opencaller.feature.callerid

/** The after-call actions that open other screens or apps. */
class AfterCallLinks(
    /** Call the number back. */
    val onCallBack: () -> Unit,
    /** Write a text message to the number. */
    val onMessage: () -> Unit,
    /** Add the number to the contacts. */
    val onSaveContact: () -> Unit,
    /** Report the number (the app's report sheet). */
    val onReport: () -> Unit,
    /** Close the screen. */
    val onClose: () -> Unit,
)
