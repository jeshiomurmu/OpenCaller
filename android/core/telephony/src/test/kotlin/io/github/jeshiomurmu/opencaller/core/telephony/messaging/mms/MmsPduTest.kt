package io.github.jeshiomurmu.opencaller.core.telephony.messaging.mms

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Builds PDU bytes by hand for the tests. */
private class Pdu {
    private val out = ByteArrayOutputStream()

    fun b(vararg values: Int) = apply { values.forEach { out.write(it) } }

    fun text(value: String) = apply { b(*value.toByteArray(Charsets.UTF_8).map { it.toInt() and 0xFF }.toIntArray(), 0) }

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }

    fun bytes(): ByteArray = out.toByteArray()
}

class MmsPduTest {
    @Test
    fun aDownloadNoticeGivesTheLocationSenderAndSize() {
        val from = Pdu().b(0x80).text("+15551234567/TYPE=PLMN").bytes()
        val pdu =
            Pdu()
                .b(0x8C, 0x82)
                .b(0x98)
                .text("T123")
                .b(0x8D, 0x92)
                .b(0x89, from.size)
                .raw(from)
                .b(0x8A, 0x80)
                .b(0x8E, 0x02, 0x10, 0x00)
                .b(0x88, 0x04, 0x81, 0x02, 0x0E, 0x10)
                .b(0x83)
                .text("http://mmsc.example/abc")
                .bytes()

        val notice = MmsPduParser.parse(pdu) as NotificationInd
        assertEquals("T123", notice.transactionId)
        assertEquals("+15551234567", notice.from)
        assertEquals(4096L, notice.messageSize)
        assertNull("a relative expiry isn't kept", notice.expiry)
        assertEquals("http://mmsc.example/abc", notice.contentLocation)
    }

    @Test
    fun tcSms003ADownloadedMessageGivesItsPartsAndPeople() {
        val image = byteArrayOf(1, 2, 3, 4, 5)
        val pdu = retrieveConf(image)
        val message = MmsPduParser.parse(pdu) as RetrieveConf
        assertEquals("+15550001111", message.from)
        assertEquals(listOf("+15550002222", "+15550003333"), message.to)
        assertEquals(0x65000000L, message.date)
        assertEquals("Fotos 📷", message.subject)
        assertEquals("application/vnd.wap.multipart.mixed", message.contentType)
        val (first, second) = message.parts
        assertTrue(first.isText)
        assertEquals("Hallo, schön!", first.text())
        assertEquals("t.txt", first.contentLocation)
        assertEquals("image/jpeg", second.contentType)
        assertEquals("img1", second.contentId)
        assertEquals("cat.jpg", second.filename)
        assertArrayEquals("base64 is decoded", image, second.data)
    }

    @Test
    fun aComposedMessageReadsBackWithItsPresentation() {
        val image = MmsImage("image/jpeg", ByteArray(300) { it.toByte() })
        val request =
            MmsMessageBuilder.build(
                "T42",
                listOf("+1 (555) 000-2222", "+15550003333", "+15550003333"),
                "Hi all, ça va? 👋",
                listOf(image),
            )
        val bytes = MmsPduComposer.compose(request)
        assertEquals(0x8C, bytes[0].toInt() and 0xFF)
        assertEquals(0x80, bytes[1].toInt() and 0xFF)

        // The same headers and body read as a downloaded message.
        bytes[1] = 0x84.toByte()
        val read = MmsPduParser.parse(bytes) as RetrieveConf
        assertEquals("T42", read.transactionId)
        assertEquals("a group message goes to each person once", listOf("+15550002222", "+15550003333"), read.to)
        assertEquals("application/vnd.wap.multipart.related", read.contentType)
        assertEquals(listOf("application/smil", "image/jpeg", "text/plain"), read.parts.map { it.contentType })
        val smil = read.parts[0].text()
        assertTrue(smil, "src=\"image0.jpg\"" in smil && "src=\"text0.txt\"" in smil)
        assertArrayEquals(image.data, read.parts[1].data)
        assertEquals("Hi all, ça va? 👋", read.parts[2].text())
        assertEquals("text0", read.parts[2].contentId)
    }

    @Test
    fun theCentresAnswersAreRead() {
        val accepted =
            Pdu()
                .b(0x8C, 0x81, 0x98)
                .text("T1")
                .b(0x8D, 0x92, 0x92, 0x80, 0x8B)
                .text("mid")
                .bytes()
        val conf = MmsPduParser.parse(accepted) as SendConf
        assertTrue(conf.isAccepted)
        assertEquals("mid", conf.messageId)

        val report =
            Pdu()
                .b(
                    0x8C,
                    0x86,
                    0x8D,
                    0x92,
                    0x8B,
                ).text("mid")
                .b(0x97)
                .text("+15550002222/TYPE=PLMN")
                .b(0x85, 0x01, 0x10, 0x95, 0x81)
                .bytes()
        val delivery = MmsPduParser.parse(report) as DeliveryInd
        assertEquals("mid", delivery.messageId)
        assertEquals("+15550002222", delivery.to)
        assertEquals(MmsHeaders.STATUS_RETRIEVED, delivery.status)
    }

    @Test
    fun theDownloadAnswersAreWrittenExactly() {
        assertArrayEquals(
            Pdu()
                .b(0x8C, 0x83, 0x98)
                .text("T1")
                .b(0x8D, 0x92, 0x95, 0x81)
                .bytes(),
            MmsPduComposer.compose(NotifyRespInd("T1", MmsHeaders.STATUS_RETRIEVED)),
        )
        assertArrayEquals(
            Pdu()
                .b(0x8C, 0x85, 0x98)
                .text("T1")
                .b(0x8D, 0x92)
                .bytes(),
            MmsPduComposer.compose(AcknowledgeInd("T1")),
        )
    }

    @Test
    fun otherOrBrokenPdusAreRefused() {
        assertNull("read-orig is not handled", MmsPduParser.parse(Pdu().b(0x8C, 0x88, 0x8D, 0x92).bytes()))
        val truncated = Pdu().b(0x8C, 0x82, 0x98, 'T'.code).bytes()
        assertTrue(runCatching { MmsPduParser.parse(truncated) }.exceptionOrNull() is MalformedPduException)
        val noLocation = Pdu().b(0x8C, 0x82, 0x98).text("T").bytes()
        assertTrue(runCatching { MmsPduParser.parse(noLocation) }.exceptionOrNull() is MalformedPduException)
    }

    @Test
    fun uintvarsAndLongLengthsRoundTrip() {
        listOf(0L, 127L, 128L, 16_383L, 16_384L, 2_097_152L).forEach { value ->
            val bytes = WspWriter().apply { uintvar(value) }.toByteArray()
            assertEquals(value, WspReader(bytes).uintvar())
        }
        val long = "x".repeat(200) + "é"
        val bytes = WspWriter().apply { encodedString(long) }.toByteArray()
        assertEquals("a value over 30 bytes uses the length quote", 31, bytes[0].toInt())
        assertEquals(long, WspReader(bytes).encodedString())
    }

    @Test
    fun aTextOnlyMessageStillHasItsLayout() {
        val request = MmsMessageBuilder.build("T", listOf("+15550002222", "+15550003333"), "Dinner at 8?", emptyList())
        assertEquals(listOf("application/smil", "text/plain"), request.parts.map { it.contentType })
        assertTrue(request.parts[0].text().contains("text0.txt"))
        assertTrue(runCatching { MmsMessageBuilder.build("T", emptyList(), "x", emptyList()) }.isFailure)
        assertTrue(runCatching { MmsMessageBuilder.build("T", listOf("+1"), " ", emptyList()) }.isFailure)
    }

    // A retrieve-conf from +15550001111 to two people with a UTF-8 subject and [twoParts].
    private fun retrieveConf(image: ByteArray): ByteArray {
        val subject = Pdu().b(0xEA).text("Fotos 📷").bytes()
        return Pdu()
            .b(0x8C, 0x84)
            .b(0x98)
            .text("T9")
            .b(0x8D, 0x92)
            .b(0x8B)
            .text("m-1")
            .b(0x85, 0x04, 0x65, 0x00, 0x00, 0x00)
            .b(0x89, 0x0E, 0x80)
            .text("+15550001111")
            .b(0x97)
            .text("+15550002222/TYPE=PLMN")
            .b(0x97)
            .text("+15550003333/TYPE=PLMN")
            .b(0x96, subject.size)
            .raw(subject)
            .b(0x84, 0xA3)
            .raw(twoParts(image))
            .bytes()
    }

    // A multipart body: a text with an untyped charset parameter, then [image] base64-encoded with a quoted
    // Content-ID and a disposition filename.
    private fun twoParts(image: ByteArray): ByteArray {
        val textType =
            Pdu()
                .b(0x83)
                .text("charset")
                .text("utf-8")
                .bytes()
        val textHeaders =
            Pdu()
                .b(textType.size)
                .raw(textType)
                .b(0x8E)
                .text("t.txt")
                .bytes()
        val text = "Hallo, schön!".toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encode(image)
        val disposition = Pdu().b(0x81, 0x98).text("cat.jpg").bytes()
        val imageHeaders =
            Pdu()
                .b(0x9E)
                .b(0xC0, 0x22)
                .text("<img1>")
                .b(0xC5, disposition.size)
                .raw(disposition)
                .text("Content-Transfer-Encoding")
                .text("base64")
                .bytes()
        return Pdu()
            .b(0x02)
            .b(textHeaders.size, text.size)
            .raw(textHeaders)
            .raw(text)
            .b(imageHeaders.size, encoded.size)
            .raw(imageHeaders)
            .raw(encoded)
            .bytes()
    }
}
