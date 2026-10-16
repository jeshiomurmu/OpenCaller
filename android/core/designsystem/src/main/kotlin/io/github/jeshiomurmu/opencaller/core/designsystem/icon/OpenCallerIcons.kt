package io.github.jeshiomurmu.opencaller.core.designsystem.icon

import androidx.annotation.DrawableRes
import io.github.jeshiomurmu.opencaller.core.designsystem.R

/** Icons (Material Symbols Outlined, Apache-2.0) used across the app. Tint them with theme colors. */
object OpenCallerIcons {
    /** Calls tab. */
    @get:DrawableRes val Calls: Int = R.drawable.ic_call

    /** Messages tab. */
    @get:DrawableRes val Messages: Int = R.drawable.ic_chat

    /** Contacts tab and contact name source. */
    @get:DrawableRes val Contacts: Int = R.drawable.ic_contacts

    /** Protection tab. */
    @get:DrawableRes val Protection: Int = R.drawable.ic_shield

    /** Me tab. */
    @get:DrawableRes val Me: Int = R.drawable.ic_person

    /** Self-registered name source. */
    @get:DrawableRes val SelfRegistered: Int = R.drawable.ic_badge

    /** Verified business. */
    @get:DrawableRes val Verified: Int = R.drawable.ic_verified

    /** Sender registry name source. */
    @get:DrawableRes val SenderRegistry: Int = R.drawable.ic_inbox

    /** Community label name source. */
    @get:DrawableRes val Community: Int = R.drawable.ic_groups

    /** Name inferred on this device. */
    @get:DrawableRes val Device: Int = R.drawable.ic_smartphone

    /** Likely scam / spam. */
    @get:DrawableRes val Danger: Int = R.drawable.ic_dangerous

    /** Suspicious. */
    @get:DrawableRes val Warning: Int = R.drawable.ic_warning
}
