package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

/** An image to send, already scaled and compressed to fit the carrier's message size. */
class MmsImage(
    /** `image/jpeg`, `image/png` or `image/gif`. */
    val contentType: String,
    val data: ByteArray,
)

/**
 * Builds the M-Send.req for a text and images, with a SMIL presentation (OMA MMS Conformance: parts in a
 * multipart/related body, the SMIL part first) so phones lay the images out above the text.
 */
object MmsMessageBuilder {
    /** The message to [recipients] (several for a group message). */
    fun build(
        transactionId: String,
        recipients: List<String>,
        text: String?,
        images: List<MmsImage>,
        subject: String? = null,
        deliveryReport: Boolean = true,
    ): SendReq {
        require(recipients.isNotEmpty()) { "an MMS needs a recipient" }
        require(!text.isNullOrBlank() || images.isNotEmpty()) { "an MMS needs a text or an image" }
        val media =
            buildList {
                images.forEachIndexed { index, image ->
                    val name = "image$index.${extensionOf(image.contentType)}"
                    add(MmsPart(image.contentType, image.data, name = name, contentId = "image$index", contentLocation = name))
                }
                text?.takeIf(String::isNotBlank)?.let {
                    add(
                        MmsPart(
                            MmsPart.TEXT_PLAIN,
                            it.toByteArray(Charsets.UTF_8),
                            charset = MmsCharsets.UTF_8,
                            name = TEXT_NAME,
                            contentId = "text0",
                            contentLocation = TEXT_NAME,
                        ),
                    )
                }
            }
        val smil =
            MmsPart(
                MmsPart.APPLICATION_SMIL,
                smilFor(media).toByteArray(Charsets.UTF_8),
                charset = MmsCharsets.UTF_8,
                name = SMIL_NAME,
                contentId = SMIL_ID,
                contentLocation = SMIL_NAME,
            )
        return SendReq(transactionId, recipients.distinct(), subject, listOf(smil) + media, deliveryReport)
    }

    // One slide per image, the text on the last slide.
    private fun smilFor(parts: List<MmsPart>): String {
        val images = parts.filterNot(MmsPart::isText)
        val text = parts.firstOrNull(MmsPart::isText)
        val slides =
            if (images.isEmpty()) {
                listOf(textRef(text))
            } else {
                images.mapIndexed { index, image ->
                    "<img src=\"${image.contentLocation}\" region=\"Image\"/>" + if (index == images.lastIndex) textRef(text) else ""
                }
            }
        return buildString {
            append("<smil><head><layout><root-layout/>")
            append("<region id=\"Image\" fit=\"meet\" top=\"0\" left=\"0\" height=\"80%\" width=\"100%\"/>")
            append("<region id=\"Text\" top=\"80%\" left=\"0\" height=\"20%\" width=\"100%\"/>")
            append("</layout></head><body>")
            slides.forEach { append("<par dur=\"5000ms\">").append(it).append("</par>") }
            append("</body></smil>")
        }
    }

    private fun textRef(text: MmsPart?): String = text?.let { "<text src=\"${it.contentLocation}\" region=\"Text\"/>" }.orEmpty()

    private fun extensionOf(contentType: String): String =
        when (contentType.lowercase()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            else -> "jpg"
        }

    private const val SMIL_ID = "smil"
    private const val SMIL_NAME = "smil.xml"
    private const val TEXT_NAME = "text0.txt"
}
