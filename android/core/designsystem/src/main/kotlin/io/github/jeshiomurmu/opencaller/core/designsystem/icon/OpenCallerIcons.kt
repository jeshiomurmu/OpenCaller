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

    /** Suspicious; also degraded-mode warnings. */
    @get:DrawableRes val Warning: Int = R.drawable.ic_warning

    /** Notifications permission. */
    @get:DrawableRes val Notifications: Int = R.drawable.ic_notifications

    /** Privacy. */
    @get:DrawableRes val Privacy: Int = R.drawable.ic_lock

    /** Granted / done. */
    @get:DrawableRes val Granted: Int = R.drawable.ic_check_circle

    /** Caller ID drawn over other apps. */
    @get:DrawableRes val Overlay: Int = R.drawable.ic_layers

    /** Hang up or decline (answering uses [Calls]). */
    @get:DrawableRes val CallEnd: Int = R.drawable.ic_call_end

    /** Microphone muted. */
    @get:DrawableRes val MicOff: Int = R.drawable.ic_mic_off

    /** Loudspeaker. */
    @get:DrawableRes val Speaker: Int = R.drawable.ic_volume_up

    /** Bluetooth audio. */
    @get:DrawableRes val Bluetooth: Int = R.drawable.ic_bluetooth

    /** Wired headset. */
    @get:DrawableRes val Headset: Int = R.drawable.ic_headphones

    /** Phone earpiece. */
    @get:DrawableRes val Earpiece: Int = R.drawable.ic_phone_in_talk

    /** Keypad. */
    @get:DrawableRes val Dialpad: Int = R.drawable.ic_dialpad

    /** Hold. */
    @get:DrawableRes val Hold: Int = R.drawable.ic_pause

    /** Add a call. */
    @get:DrawableRes val AddCall: Int = R.drawable.ic_add_call

    /** Merge calls. */
    @get:DrawableRes val Merge: Int = R.drawable.ic_call_merge

    /** Swap calls. */
    @get:DrawableRes val Swap: Int = R.drawable.ic_swap_calls

    /** Delete the last digit. */
    @get:DrawableRes val Backspace: Int = R.drawable.ic_backspace

    /** Reply with a message. */
    @get:DrawableRes val Message: Int = R.drawable.ic_sms

    /** SIM card. */
    @get:DrawableRes val SimCard: Int = R.drawable.ic_sim_card

    /** Incoming call in the call log. */
    @get:DrawableRes val CallReceived: Int = R.drawable.ic_call_received

    /** Outgoing call in the call log. */
    @get:DrawableRes val CallMade: Int = R.drawable.ic_call_made

    /** Missed call in the call log. */
    @get:DrawableRes val CallMissed: Int = R.drawable.ic_call_missed

    /** Blocked call, Block action. */
    @get:DrawableRes val Block: Int = R.drawable.ic_block

    /** Voicemail. */
    @get:DrawableRes val Voicemail: Int = R.drawable.ic_voicemail

    /** Delete. */
    @get:DrawableRes val Delete: Int = R.drawable.ic_delete

    /** Not a favourite (tap to add). */
    @get:DrawableRes val Star: Int = R.drawable.ic_star

    /** A favourite. */
    @get:DrawableRes val StarFilled: Int = R.drawable.ic_star_filled

    /** Edit. */
    @get:DrawableRes val Edit: Int = R.drawable.ic_edit

    /** Move up. */
    @get:DrawableRes val MoveUp: Int = R.drawable.ic_arrow_upward

    /** New contact, save to contacts. */
    @get:DrawableRes val PersonAdd: Int = R.drawable.ic_person_add

    /** Search. */
    @get:DrawableRes val Search: Int = R.drawable.ic_search

    /** Send a message. */
    @get:DrawableRes val Send: Int = R.drawable.ic_send

    /** Attach a picture. */
    @get:DrawableRes val Image: Int = R.drawable.ic_image

    /** Download (an MMS waiting on the carrier's server). */
    @get:DrawableRes val Download: Int = R.drawable.ic_download

    /** Start something new (a message). */
    @get:DrawableRes val Add: Int = R.drawable.ic_add
}
