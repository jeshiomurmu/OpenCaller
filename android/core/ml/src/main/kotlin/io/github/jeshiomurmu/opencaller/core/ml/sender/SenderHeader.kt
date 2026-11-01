package io.github.jeshiomurmu.opencaller.core.ml.sender

/** The traffic type an Indian sender header ends with (TRAI's DLT rules: `-S`, `-T`, `-P`, `-G`). */
enum class HeaderType {
    /** `S`: service messages, including one-time codes and account updates. */
    SERVICE,

    /** `T`: transactional messages from banks (debits, credits, codes). */
    TRANSACTIONAL,

    /** `P`: promotional messages. */
    PROMOTIONAL,

    /** `G`: government messages. */
    GOVERNMENT,
}

/**
 * An Indian commercial sender header, `<2 letters>-<HEADER>[-<S|T|P|G>]` (e.g. `VM-HDFCBK-T`): the first letter
 * names the operator and the second the telecom circle the message came through, [header] is the brand's
 * registered header (3–9 letters or digits; 6 for most) and [type] the traffic type, absent in the older form.
 */
data class SenderHeader(
    val route: String,
    val header: String,
    val type: HeaderType?,
) {
    /** Parses headers. */
    companion object {
        private val INDIAN = Regex("""^([A-Za-z]{2})-([A-Za-z0-9]{3,9})(?:-([STPGstpg]))?$""")
        private val TYPES =
            mapOf(
                'S' to HeaderType.SERVICE,
                'T' to HeaderType.TRANSACTIONAL,
                'P' to HeaderType.PROMOTIONAL,
                'G' to HeaderType.GOVERNMENT,
            )

        /** [sender] as an Indian header, or `null` when it has another form. */
        fun parseIndian(sender: String): SenderHeader? {
            val match = INDIAN.matchEntire(sender.trim()) ?: return null
            val (route, header, type) = match.destructured
            return SenderHeader(route.uppercase(), header.uppercase(), type.firstOrNull()?.uppercaseChar()?.let(TYPES::getValue))
        }
    }
}
