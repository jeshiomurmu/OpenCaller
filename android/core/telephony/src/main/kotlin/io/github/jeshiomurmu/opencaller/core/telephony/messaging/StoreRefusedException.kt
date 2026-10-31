package io.github.jeshiomurmu.opencaller.core.telephony.messaging

/** Android's message store didn't take a write (it returns no row when the app isn't the default SMS app). */
internal class StoreRefusedException(
    what: String,
) : IllegalStateException("$what was refused")
