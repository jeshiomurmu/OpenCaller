package io.github.jeshiomurmu.opencaller.core.telephony.dialing

/** What tapping Call on the dial pad does with what was typed. */
sealed interface DialAction {
    /** Call [dialString] (a number, possibly with pauses `,` or waits `;`). */
    data class Number(
        val dialString: String,
    ) : DialAction

    /**
     * An MMI or USSD code such as `*123#` (balance) or `*#21#` (call forwarding status), passed to the carrier
     * unchanged: placed through Telecom like a number, where Telephony recognises and sends it.
     */
    data class CarrierCode(
        val code: String,
    ) : DialAction

    /**
     * A secret code `*#*#<digits>#*#*` meant for an app on the phone (for example a testing menu); [digits] is what
     * Android's `TelephonyManager.sendDialerSpecialCode` takes. It is handed to Android, never dialled.
     */
    data class SecretCode(
        val digits: String,
    ) : DialAction
}

/** Sorts dial pad input into a [DialAction]. Pure; emergency numbers are recognised by the device, not here. */
object DialStrings {
    // AOSP's dialer format for codes handled on the phone.
    private val SECRET_CODE = Regex("""^\*#\*#(\d+)#\*#\*$""")

    /** What Call does with [input]; `null` when there is nothing to dial. */
    fun classify(input: String): DialAction? {
        val text = input.trim()
        val secret = SECRET_CODE.matchEntire(text)
        // TelecomManager.handleMmi: "MMI codes are any sequence of characters entered into the dialpad that contain a
        // '*' or '#'". handleMmi itself needs MODIFY_PHONE_STATE (system apps only), so they go through placeCall.
        return when {
            text.isEmpty() -> null
            secret != null -> DialAction.SecretCode(secret.groupValues[1])
            text.any { it == '*' || it == '#' } -> DialAction.CarrierCode(text)
            else -> DialAction.Number(text)
        }
    }
}
