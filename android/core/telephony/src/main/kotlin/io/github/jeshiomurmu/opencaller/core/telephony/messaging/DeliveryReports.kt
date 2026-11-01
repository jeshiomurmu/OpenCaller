package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.model.DeliveryState

/**
 * Reads the status of an SMS delivery report (`SmsMessage.getStatus()` of the report PDU). In the 3GPP format it is
 * the TP-Status octet (3GPP TS 23.040 §9.2.3.15): 0x00–0x1F completed, 0x20–0x3F still trying, 0x40 and above failed.
 * In the 3GPP2 format Android puts the error class in bits 24–25 (0 no error, 2 temporary, 3 permanent), as AOSP's
 * Messaging app reads it.
 */
object DeliveryReports {
    /** The 3GPP SMS format name used by `SmsMessage.createFromPdu`. */
    const val FORMAT_3GPP = "3gpp"

    /** The 3GPP2 (CDMA) format name. */
    const val FORMAT_3GPP2 = "3gpp2"

    /** The delivery state for [status] in [format]. */
    fun stateOf(
        status: Int,
        format: String?,
    ): DeliveryState =
        if (format == FORMAT_3GPP2) {
            when ((status ushr CDMA_ERROR_CLASS_SHIFT) and CDMA_ERROR_CLASS_MASK) {
                CDMA_NO_ERROR -> DeliveryState.DELIVERED
                CDMA_TEMPORARY -> DeliveryState.PENDING
                else -> DeliveryState.FAILED
            }
        } else {
            when {
                status >= GSM_FAILED -> DeliveryState.FAILED
                status >= GSM_PENDING -> DeliveryState.PENDING
                else -> DeliveryState.DELIVERED
            }
        }

    /**
     * The state of a whole multipart message after part [partIndex] of [partCount] reported [part]: a failed part
     * fails the message at once; the message counts as delivered when the last part is.
     */
    fun combine(
        part: DeliveryState,
        partIndex: Int,
        partCount: Int,
    ): DeliveryState? =
        when {
            part == DeliveryState.FAILED -> DeliveryState.FAILED
            part == DeliveryState.DELIVERED && partIndex >= partCount - 1 -> DeliveryState.DELIVERED
            else -> null
        }

    /**
     * The delivery state of an MMS delivery report's Status (OMA MMS Encapsulation §7.3.54): Retrieved or Forwarded
     * is delivered, Deferred or Indeterminate still waits, Expired, Rejected, Unrecognised and Unreachable failed.
     */
    fun stateOfMms(status: Int): DeliveryState =
        when (status) {
            MMS_RETRIEVED, MMS_FORWARDED -> DeliveryState.DELIVERED
            MMS_DEFERRED, MMS_INDETERMINATE -> DeliveryState.PENDING
            else -> DeliveryState.FAILED
        }

    private const val MMS_RETRIEVED = 0x81
    private const val MMS_DEFERRED = 0x83
    private const val MMS_INDETERMINATE = 0x85
    private const val MMS_FORWARDED = 0x86
    private const val GSM_PENDING = 0x20
    private const val GSM_FAILED = 0x40
    private const val CDMA_ERROR_CLASS_SHIFT = 24
    private const val CDMA_ERROR_CLASS_MASK = 0x03
    private const val CDMA_NO_ERROR = 0
    private const val CDMA_TEMPORARY = 2
}
